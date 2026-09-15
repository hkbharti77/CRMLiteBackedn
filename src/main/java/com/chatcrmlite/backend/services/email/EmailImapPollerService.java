package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.dto.email.InboundEmailDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailImapPollerService {

    private final EmailInboundReplyService inboundReplyService;

    @Value("${email.imap.enabled:false}")
    private boolean imapEnabled;

    @Value("${email.imap.host:}")
    private String imapHost;

    @Value("${email.imap.port:993}")
    private int imapPort;

    @Value("${email.imap.username:}")
    private String imapUsername;

    @Value("${email.imap.password:}")
    private String imapPassword;

    /**
     * Poll IMAP inbox every 5 minutes if enabled in properties.
     */
    @Scheduled(fixedDelayString = "${email.imap.poll-interval-ms:300000}")
    public void pollInboxForReplies() {
        if (!imapEnabled || imapHost == null || imapHost.isBlank() || imapUsername == null || imapUsername.isBlank()) {
            return;
        }

        log.info("[IMAPPoller] Polling inbox on host {} for user {}", imapHost, imapUsername);
        try {
            // IMAP connection skeleton for inbox synchronization when IMAP polling is active
            // Extracted messages are converted to InboundEmailDTO and submitted to processInboundReply
        } catch (Exception e) {
            log.error("[IMAPPoller] Error during IMAP inbox polling", e);
        }
    }
}
