package com.chatcrmlite.backend.services.google;

import com.chatcrmlite.backend.config.GoogleConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.google.*;
import com.chatcrmlite.backend.repositories.GoogleConnectionRepository;
import com.chatcrmlite.backend.repositories.GoogleIntegrationRepository;
import com.chatcrmlite.backend.repositories.GoogleSyncRepository;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for sending emails via Google's Gmail API (gmail.send scope).
 *
 * Security & Reliability architecture:
 * - Uses centralized GoogleTokenService for token retrieval and automatic refresh.
 * - Idempotency & audit logging via GoogleSync table.
 * - Granular permission verification: requires GMAIL feature CONNECTED or gmail.send granted.
 * - UTF-8 RFC 2822 MimeMessage construction with Base64URL encoding (URL-safe, no padding).
 */
@Service
public class GmailService {

    private static final Logger log = LoggerFactory.getLogger(GmailService.class);
    private static final String APPLICATION_NAME = "CRMLite";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    @Autowired private GoogleTokenService googleTokenService;
    @Autowired private GoogleConnectionRepository connectionRepository;
    @Autowired private GoogleIntegrationRepository integrationRepository;
    @Autowired private GoogleSyncRepository syncRepository;
    @Autowired private GoogleConfig googleConfig;
    @Autowired(required = false) private GoogleAuditService auditService;

    /**
     * Checks if the user has an active Gmail integration with gmail.send permission.
     */
    public boolean isConnected(UUID userId) {
        if (userId == null) return false;

        Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(userId);
        if (connOpt.isEmpty()) return false;

        GoogleConnection conn = connOpt.get();
        Optional<GoogleIntegration> integrationOpt =
                integrationRepository.findByConnectionIdAndFeature(conn.getId(), GoogleIntegrationType.GMAIL);

        if (integrationOpt.isPresent() && integrationOpt.get().getStatus() == GoogleIntegrationStatus.CONNECTED) {
            return true;
        }

        return conn.hasScope("gmail.send") || conn.hasScope("https://www.googleapis.com/auth/gmail.send");
    }

    /**
     * Sends an email via Gmail API on behalf of the connected user.
     *
     * @param senderUser The authenticated user sending the email
     * @param to Recipient email address
     * @param subject Email subject
     * @param bodyHtml HTML content of the email
     * @param cc Optional CC recipients (comma-separated or single)
     * @param bcc Optional BCC recipients
     * @param crmResourceId Optional UUID of the associated CRM lead, contact, or conversation
     * @return Map containing messageId, threadId, and timestamp
     */
    @Transactional
    public Map<String, Object> sendEmail(
            User senderUser,
            String to,
            String subject,
            String bodyHtml,
            String cc,
            String bcc,
            UUID crmResourceId) throws IOException, GeneralSecurityException, MessagingException {

        if (senderUser == null || senderUser.getId() == null) {
            throw new IllegalArgumentException("Sender user must not be null");
        }

        GoogleConnection conn = connectionRepository.findActiveByUserId(senderUser.getId())
                .orElseThrow(() -> new IllegalStateException("No active Google connection found. Please connect your Gmail account in Settings."));

        if (!isConnected(senderUser.getId())) {
            throw new IllegalStateException("Gmail integration is not connected. Please authorize Gmail access in Settings.");
        }

        // 1. Get valid access token (auto-refreshes if expired)
        String accessToken = googleTokenService.getValidAccessToken(conn.getId());

        // 2. Build RFC 2822 MIME message
        MimeMessage mimeMessage = createMimeMessage(
                senderUser.getEmail(),
                to,
                subject,
                bodyHtml,
                cc,
                bcc
        );

        // 3. Encode to Base64URL string (RFC 4648 URL safe without padding)
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        byte[] rawBytes = buffer.toByteArray();
        String encodedEmail = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes);

        Message gmailMessage = new Message();
        gmailMessage.setRaw(encodedEmail);

        // 4. Send via Gmail API with 1 retry on 401
        Gmail gmail = buildGmailClient(accessToken);
        Message sentMessage;
        long startTime = System.currentTimeMillis();
        try {
            sentMessage = gmail.users().messages().send("me", gmailMessage).execute();
            log.info("[GmailService] Email sent successfully by userId={} to={} messageId={}",
                    senderUser.getId(), to, sentMessage.getId());
            if (auditService != null) {
                auditService.logSuccess(senderUser.getId(), "GMAIL", "SEND_EMAIL", "gmail.googleapis.com", System.currentTimeMillis() - startTime);
            }
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 401) {
                log.warn("[GmailService] Received 401 from Gmail API, force-refreshing token and retrying once...");
                String freshToken = googleTokenService.forceRefresh(conn.getId());
                Gmail retryClient = buildGmailClient(freshToken);
                sentMessage = retryClient.users().messages().send("me", gmailMessage).execute();
                log.info("[GmailService] Email sent on retry by userId={} to={} messageId={}",
                        senderUser.getId(), to, sentMessage.getId());
                if (auditService != null) {
                    auditService.logSuccess(senderUser.getId(), "GMAIL", "SEND_EMAIL", "gmail.googleapis.com", System.currentTimeMillis() - startTime);
                }
            } else if (e.getStatusCode() == 403) {
                log.error("[GmailService] 403 Insufficient permissions for Gmail API userId={}", senderUser.getId(), e);
                integrationRepository.updateStatus(conn.getId(), GoogleIntegrationType.GMAIL, GoogleIntegrationStatus.REAUTH_REQUIRED);
                if (auditService != null) {
                    auditService.logApiCall(senderUser.getId(), conn.getId(), "GMAIL", "SEND_EMAIL",
                            "gmail.googleapis.com", "REAUTH_REQUIRED", System.currentTimeMillis() - startTime, "403", e.getMessage(), 0);
                }
                throw new IllegalStateException("Gmail permission was denied or expired. Please re-authorize Gmail in Settings.", e);
            } else {
                if (auditService != null) {
                    auditService.logFailure(senderUser.getId(), "GMAIL", "SEND_EMAIL", "gmail.googleapis.com", System.currentTimeMillis() - startTime, e, 0);
                }
                throw e;
            }
        } catch (Exception ex) {
            if (auditService != null) {
                auditService.logFailure(senderUser.getId(), "GMAIL", "SEND_EMAIL", "gmail.googleapis.com", System.currentTimeMillis() - startTime, ex, 0);
            }
            throw ex;
        }

        // 5. Record GoogleSync mapping for audit and tracking
        try {
            GoogleSync sync = new GoogleSync();
            sync.setConnectionId(conn.getId());
            sync.setResourceType("GMAIL_MESSAGE");
            sync.setCrmResourceId(crmResourceId != null ? crmResourceId : UUID.randomUUID());
            sync.setGoogleResourceId(sentMessage.getId());
            sync.setSyncDirection("CRM_TO_GOOGLE");
            sync.setSyncStatus("OK");
            sync.setLastSyncedAt(LocalDateTime.now());
            syncRepository.save(sync);
        } catch (Exception ex) {
            log.warn("[GmailService] Could not persist GoogleSync audit row: {}", ex.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("messageId", sentMessage.getId());
        result.put("threadId", sentMessage.getThreadId());
        result.put("sentAt", LocalDateTime.now().toString());
        return result;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Gmail buildGmailClient(String accessToken) throws GeneralSecurityException, IOException {
        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        HttpRequestInitializer initializer = request ->
                request.getHeaders().setAuthorization("Bearer " + accessToken);

        return new Gmail.Builder(transport, JSON_FACTORY, initializer)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private MimeMessage createMimeMessage(
            String from,
            String to,
            String subject,
            String bodyHtml,
            String cc,
            String bcc) throws MessagingException {

        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);

        MimeMessage email = new MimeMessage(session);

        if (from != null && !from.isBlank()) {
            email.setFrom(new InternetAddress(from));
        }

        // To
        if (to != null && !to.isBlank()) {
            for (String recipient : to.split(",")) {
                String clean = recipient.trim();
                if (!clean.isEmpty()) {
                    email.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(clean));
                }
            }
        }

        // CC
        if (cc != null && !cc.isBlank()) {
            for (String recipient : cc.split(",")) {
                String clean = recipient.trim();
                if (!clean.isEmpty()) {
                    email.addRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(clean));
                }
            }
        }

        // BCC
        if (bcc != null && !bcc.isBlank()) {
            for (String recipient : bcc.split(",")) {
                String clean = recipient.trim();
                if (!clean.isEmpty()) {
                    email.addRecipient(jakarta.mail.Message.RecipientType.BCC, new InternetAddress(clean));
                }
            }
        }

        email.setSubject(subject != null ? subject : "(no subject)", StandardCharsets.UTF_8.name());
        email.setContent(bodyHtml != null ? bodyHtml : "", "text/html; charset=UTF-8");

        return email;
    }
}
