package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.dto.email.InboundEmailDTO;
import com.chatcrmlite.backend.models.EmailProvider;
import com.chatcrmlite.backend.repositories.EmailProviderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailImapPollerService {

    private final EmailInboundReplyService inboundReplyService;
    private final EmailProviderRepository emailProviderRepository;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${email.imap.enabled:true}")
    private boolean imapEnabled;

    @Value("${email.imap.host:}")
    private String globalImapHost;

    @Value("${email.imap.port:993}")
    private int globalImapPort;

    @Value("${email.imap.username:}")
    private String globalImapUsername;

    @Value("${email.imap.password:}")
    private String globalImapPassword;

    /**
     * Poll IMAP inbox every 2 minutes for new unread email replies.
     */
    @Scheduled(fixedDelayString = "${email.imap.poll-interval-ms:120000}")
    public void pollInboxForReplies() {
        if (!imapEnabled) {
            return;
        }

        // 1. Poll global application properties IMAP if configured
        if (globalImapHost != null && !globalImapHost.isBlank() && globalImapUsername != null && !globalImapUsername.isBlank()) {
            pollSingleImapAccount(globalImapHost, globalImapPort, globalImapUsername, globalImapPassword);
        }

        // 2. Poll tenant-configured SMTP/IMAP email providers
        try {
            List<EmailProvider> providers = emailProviderRepository.findAll();
            for (EmailProvider provider : providers) {
                if ("SMTP".equalsIgnoreCase(provider.getProviderType()) && provider.getCredentialsPayload() != null) {
                    try {
                        Map<String, Object> creds = objectMapper.readValue(provider.getCredentialsPayload(), Map.class);
                        String username = String.valueOf(creds.getOrDefault("username", provider.getFromEmail())).trim();
                        String password = String.valueOf(creds.getOrDefault("password", "")).trim();
                        String smtpHost = String.valueOf(creds.getOrDefault("host", "smtp.gmail.com")).trim();

                        String imapHost = String.valueOf(creds.getOrDefault("imapHost", "")).trim();
                        int imapPort = 993;
                        try {
                            if (creds.containsKey("imapPort")) {
                                imapPort = Integer.parseInt(String.valueOf(creds.get("imapPort")).trim());
                            }
                        } catch (Exception ignored) {}

                        if (imapHost.isBlank()) {
                            if (smtpHost.contains("gmail.com")) {
                                imapHost = "imap.gmail.com";
                            } else if (smtpHost.contains("zoho.com")) {
                                imapHost = "imap.zoho.com";
                            } else if (smtpHost.contains("office365.com") || smtpHost.contains("outlook.com")) {
                                imapHost = "outlook.office365.com";
                            }
                        }

                        if (!imapHost.isBlank() && !username.isBlank() && !password.isBlank()) {
                            pollSingleImapAccount(imapHost, imapPort, username, password);
                        }
                    } catch (Exception e) {
                        log.warn("[IMAPPoller] Could not parse credentials for provider ID {}: {}", provider.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[IMAPPoller] Error scanning provider IMAP accounts", e);
        }
    }

    public void pollSingleImapAccount(String host, int port, String username, String password) {
        log.info("[IMAPPoller] Checking inbox on {} for account {}", host, username);
        Store store = null;
        Folder inbox = null;
        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", host);
            props.put("mail.imaps.port", String.valueOf(port));
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.timeout", "10000");
            props.put("mail.imaps.connectiontimeout", "10000");

            Session session = Session.getInstance(props);
            store = session.getStore("imaps");
            store.connect(host, port, username, password);

            inbox = store.getFolder("INBOX");
            if (inbox != null && inbox.exists()) {
                inbox.open(Folder.READ_WRITE);

                // Fetch unread messages
                Message[] unread = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                log.info("[IMAPPoller] Found {} unread messages in inbox for {}", unread.length, username);

                for (Message msg : unread) {
                    try {
                        String fromEmail = "unknown@domain.com";
                        if (msg.getFrom() != null && msg.getFrom().length > 0) {
                            Address addr = msg.getFrom()[0];
                            if (addr instanceof InternetAddress) {
                                fromEmail = ((InternetAddress) addr).getAddress();
                            } else {
                                fromEmail = addr.toString();
                            }
                        }

                        String toEmail = username;
                        Address[] recipients = msg.getRecipients(Message.RecipientType.TO);
                        if (recipients != null && recipients.length > 0) {
                            if (recipients[0] instanceof InternetAddress) {
                                toEmail = ((InternetAddress) recipients[0]).getAddress();
                            } else {
                                toEmail = recipients[0].toString();
                            }
                        }

                        String subject = msg.getSubject();
                        String textBody = getTextFromPart(msg);
                        String messageId = getHeaderValue(msg, "Message-ID");
                        if (messageId == null || messageId.isBlank()) {
                            messageId = "imap-msg-" + msg.getMessageNumber() + "-" + (msg.getReceivedDate() != null ? msg.getReceivedDate().getTime() : System.currentTimeMillis());
                        }
                        String inReplyTo = getHeaderValue(msg, "In-Reply-To");
                        String references = getHeaderValue(msg, "References");

                        InboundEmailDTO dto = InboundEmailDTO.builder()
                                .provider("IMAP")
                                .providerMessageId(messageId)
                                .fromEmail(fromEmail)
                                .toEmail(toEmail)
                                .subject(subject)
                                .textBody(textBody)
                                .inReplyTo(inReplyTo)
                                .references(references)
                                .receivedAt(msg.getReceivedDate() != null ? msg.getReceivedDate().toInstant() : Instant.now())
                                .build();

                        inboundReplyService.processInboundReply(dto);
                        msg.setFlag(Flags.Flag.SEEN, true);
                    } catch (Exception ex) {
                        log.error("[IMAPPoller] Error processing message from {}", username, ex);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[IMAPPoller] Failed to poll IMAP account {} on {}: {}", username, host, e.getMessage());
        } finally {
            try {
                if (inbox != null && inbox.isOpen()) {
                    inbox.close(false);
                }
                if (store != null && store.isConnected()) {
                    store.close();
                }
            } catch (Exception ignored) {}
        }
    }

    private String getHeaderValue(Message msg, String headerName) {
        try {
            String[] headers = msg.getHeader(headerName);
            if (headers != null && headers.length > 0) {
                return headers[0];
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getTextFromPart(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                sb.append(getTextFromPart(bp));
            }
            return sb.toString();
        }
        return "";
    }
}

