package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.config.RateLimitConfig;
import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.models.email.EmailRecipientEvent;
import com.chatcrmlite.backend.models.email.EmailTrackedLink;
import com.chatcrmlite.backend.models.email.EmailSuppressionList.SuppressionReason;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailRecipientEventRepository;
import com.chatcrmlite.backend.repositories.email.EmailTrackedLinkRepository;
import com.chatcrmlite.backend.services.email.EmailSuppressionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EmailTrackingController {

    private final EmailCampaignRecipientRepository recipientRepository;
    private final EmailRecipientEventRepository eventRepository;
    private final EmailTrackedLinkRepository linkRepository;
    private final EmailSuppressionService suppressionService;
    private final RateLimitConfig rateLimitConfig;

    // Standard 1x1 transparent PNG bytes
    private static final byte[] PNG_BYTES = new byte[]{
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47, (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0D, (byte) 0x49, (byte) 0x48, (byte) 0x44, (byte) 0x52,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
            (byte) 0x08, (byte) 0x06, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x1F, (byte) 0x15, (byte) 0xC4,
            (byte) 0x89, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0A, (byte) 0x49, (byte) 0x44, (byte) 0x41,
            (byte) 0x54, (byte) 0x78, (byte) 0x9C, (byte) 0x63, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00,
            (byte) 0x05, (byte) 0x00, (byte) 0x01, (byte) 0x0D, (byte) 0x0A, (byte) 0x2D, (byte) 0xB4, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x49, (byte) 0x45, (byte) 0x4E, (byte) 0x44, (byte) 0xAE,
            (byte) 0x42, (byte) 0x60, (byte) 0x82
    };

    @GetMapping("/t/o/{trackingToken}.png")
    public ResponseEntity<byte[]> trackOpen(@PathVariable String trackingToken, HttpServletRequest request) {
        String clientIp = getClientIp(request);
        if (!rateLimitConfig.tryConsume(clientIp, RateLimitConfig.Tier.PUBLIC)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        Optional<EmailCampaignRecipient> optRecipient = recipientRepository.findByTrackingToken(trackingToken);

        if (optRecipient.isPresent()) {
            EmailCampaignRecipient recipient = optRecipient.get();
            LocalDateTime now = LocalDateTime.now();

            // Create raw audit event
            EmailRecipientEvent event = EmailRecipientEvent.builder()
                    .tenantId(recipient.getTenantId())
                    .campaignId(recipient.getCampaignId())
                    .recipientId(recipient.getId())
                    .eventType(EmailRecipientEvent.EventType.OPENED)
                    .occurredAt(now)
                    .build();
            eventRepository.save(event);

            // Atomic DB update to preserve earliest first_opened_at timestamp
            recipientRepository.updateFirstOpenedAt(recipient.getId(), now);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        headers.setPragma("no-cache");
        headers.setExpires(0L);

        return new ResponseEntity<>(PNG_BYTES, headers, HttpStatus.OK);
    }

    @GetMapping("/t/c/{linkToken}")
    public ResponseEntity<Void> trackClickSingle(@PathVariable String linkToken, HttpServletRequest request) {
        return processClickRedirect(linkToken, null, request);
    }

    @GetMapping("/t/c/{trackingToken}")
    public ResponseEntity<Void> trackClickLegacy(@PathVariable String trackingToken, 
                                                @RequestParam(value = "l", required = false) String linkToken,
                                                HttpServletRequest request) {
        String targetToken = linkToken != null ? linkToken : trackingToken;
        return processClickRedirect(targetToken, trackingToken, request);
    }

    private ResponseEntity<Void> processClickRedirect(String linkToken, String trackingToken, HttpServletRequest request) {
        String clientIp = getClientIp(request);
        if (!rateLimitConfig.tryConsume(clientIp, RateLimitConfig.Tier.PUBLIC)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        Optional<EmailTrackedLink> optLink = linkRepository.findByLinkToken(linkToken);

        if (optLink.isPresent()) {
            EmailTrackedLink link = optLink.get();
            LocalDateTime now = LocalDateTime.now();

            String tokenToFind = trackingToken != null ? trackingToken : linkToken;
            Optional<EmailCampaignRecipient> optRecipient = recipientRepository.findByTrackingToken(tokenToFind);

            if (optRecipient.isPresent()) {
                EmailCampaignRecipient recipient = optRecipient.get();
                
                // Save raw audit event
                EmailRecipientEvent event = EmailRecipientEvent.builder()
                        .tenantId(recipient.getTenantId())
                        .campaignId(recipient.getCampaignId())
                        .recipientId(recipient.getId())
                        .eventType(EmailRecipientEvent.EventType.CLICKED)
                        .linkUrl(link.getDestinationUrl())
                        .occurredAt(now)
                        .build();
                eventRepository.save(event);

                // Atomic DB update for first_clicked_at
                recipientRepository.updateFirstClickedAt(recipient.getId(), now);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(URI.create(link.getDestinationUrl()));
            return new ResponseEntity<>(headers, HttpStatus.FOUND);
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @GetMapping(value = "/u/{trackingToken}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> handleUnsubscribeGet(@PathVariable String trackingToken, HttpServletRequest request) {
        String clientIp = getClientIp(request);
        if (!rateLimitConfig.tryConsume(clientIp, RateLimitConfig.Tier.PUBLIC)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Too many requests");
        }

        Optional<EmailCampaignRecipient> optRecipient = recipientRepository.findByTrackingToken(trackingToken);
        String recipientEmail = optRecipient.map(EmailCampaignRecipient::getEmail).orElse("your email address");

        boolean isAlreadyUnsubscribed = false;
        if (optRecipient.isPresent()) {
            EmailCampaignRecipient recipient = optRecipient.get();
            isAlreadyUnsubscribed = suppressionService.isSuppressed(recipient.getTenantId(), recipient.getEmail());
        }

        String initialHeading = isAlreadyUnsubscribed ? "You Are Unsubscribed" : "Unsubscribe Request";
        String initialText = isAlreadyUnsubscribed 
                ? "You are currently unsubscribed from marketing emails sent to <strong>" + recipientEmail + "</strong>."
                : "Are you sure you want to stop receiving marketing emails sent to <strong>" + recipientEmail + "</strong>?";
        
        String unsubBtnStyle = isAlreadyUnsubscribed ? "display:none;" : "display:inline-block;";
        String resubBtnStyle = isAlreadyUnsubscribed ? "display:inline-block;" : "display:none;";
        String statusBadgeText = isAlreadyUnsubscribed ? "Status: Unsubscribed" : "Status: Active";
        String statusBadgeClass = isAlreadyUnsubscribed ? "badge-unsub" : "badge-active";

        String html = "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>Email Preference Center</title>\n" +
                "    <style>\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background-color: #f8fafc; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; padding: 20px; }\n" +
                "        .container { background: white; padding: 40px 32px; border-radius: 20px; box-shadow: 0 10px 30px rgba(0, 0, 0, 0.08); text-align: center; max-width: 460px; width: 100%; border: 1px solid #e2e8f0; }\n" +
                "        h1 { color: #0f172a; font-size: 22px; margin-bottom: 8px; font-weight: 800; letter-spacing: -0.02em; }\n" +
                "        p { color: #64748b; font-size: 14px; line-height: 1.6; margin-bottom: 24px; }\n" +
                "        .badge { display: inline-block; padding: 4px 12px; border-radius: 9999px; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 16px; }\n" +
                "        .badge-active { background-color: #dcfce7; color: #15803d; }\n" +
                "        .badge-unsub { background-color: #fee2e2; color: #b91c1c; }\n" +
                "        .btn { border: none; padding: 12px 28px; font-size: 14px; font-weight: 700; border-radius: 12px; cursor: pointer; transition: all 0.2s ease; width: 100%; }\n" +
                "        .btn-danger { background-color: #ef4444; color: white; box-shadow: 0 4px 12px rgba(239, 68, 68, 0.25); }\n" +
                "        .btn-danger:hover { background-color: #dc2626; transform: translateY(-1px); }\n" +
                "        .btn-primary { background-color: #3b82f6; color: white; box-shadow: 0 4px 12px rgba(59, 130, 246, 0.25); }\n" +
                "        .btn-primary:hover { background-color: #2563eb; transform: translateY(-1px); }\n" +
                "        .status-msg { margin-top: 20px; padding: 12px 16px; border-radius: 12px; font-size: 14px; font-weight: 600; display: none; }\n" +
                "        .msg-unsub { background-color: #fef2f2; color: #991b1b; border: 1px solid #fecaca; }\n" +
                "        .msg-sub { background-color: #f0fdf4; color: #166534; border: 1px solid #bbf7d0; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <span id=\"statusBadge\" class=\"badge " + statusBadgeClass + "\">" + statusBadgeText + "</span>\n" +
                "        <h1 id=\"pageHeading\">" + initialHeading + "</h1>\n" +
                "        <p id=\"pageDesc\">" + initialText + "</p>\n" +
                "\n" +
                "        <div id=\"unsubBox\" style=\"" + unsubBtnStyle + "\">\n" +
                "            <button id=\"unsubBtn\" type=\"button\" class=\"btn btn-danger\">Confirm Unsubscribe</button>\n" +
                "        </div>\n" +
                "\n" +
                "        <div id=\"resubBox\" style=\"" + resubBtnStyle + "\">\n" +
                "            <button id=\"resubBtn\" type=\"button\" class=\"btn btn-primary\">Resubscribe to Emails</button>\n" +
                "        </div>\n" +
                "\n" +
                "        <div id=\"statusMsg\" class=\"status-msg\"></div>\n" +
                "    </div>\n" +
                "    <script>\n" +
                "        const unsubBtn = document.getElementById('unsubBtn');\n" +
                "        const resubBtn = document.getElementById('resubBtn');\n" +
                "        const unsubBox = document.getElementById('unsubBox');\n" +
                "        const resubBox = document.getElementById('resubBox');\n" +
                "        const heading = document.getElementById('pageHeading');\n" +
                "        const desc = document.getElementById('pageDesc');\n" +
                "        const badge = document.getElementById('statusBadge');\n" +
                "        const statusMsg = document.getElementById('statusMsg');\n" +
                "\n" +
                "        unsubBtn.addEventListener('click', function() {\n" +
                "            unsubBtn.disabled = true;\n" +
                "            unsubBtn.innerText = 'Processing...';\n" +
                "            fetch('/api/v1/u/" + trackingToken + "', { method: 'POST' }).then(function(res) {\n" +
                "                if (res.ok) {\n" +
                "                    unsubBox.style.display = 'none';\n" +
                "                    resubBox.style.display = 'block';\n" +
                "                    heading.innerText = 'You Are Unsubscribed';\n" +
                "                    desc.innerHTML = 'You are currently unsubscribed from marketing emails sent to <strong>" + recipientEmail + "</strong>.';\n" +
                "                    badge.innerText = 'Status: Unsubscribed';\n" +
                "                    badge.className = 'badge badge-unsub';\n" +
                "                    statusMsg.className = 'status-msg msg-unsub';\n" +
                "                    statusMsg.innerText = 'You have been successfully unsubscribed.';\n" +
                "                    statusMsg.style.display = 'block';\n" +
                "                } else {\n" +
                "                    unsubBtn.disabled = false;\n" +
                "                    unsubBtn.innerText = 'Confirm Unsubscribe';\n" +
                "                    alert('Failed to update subscription. Please try again.');\n" +
                "                }\n" +
                "            });\n" +
                "        });\n" +
                "\n" +
                "        resubBtn.addEventListener('click', function() {\n" +
                "            resubBtn.disabled = true;\n" +
                "            resubBtn.innerText = 'Processing...';\n" +
                "            fetch('/api/v1/u/" + trackingToken + "/resubscribe', { method: 'POST' }).then(function(res) {\n" +
                "                if (res.ok) {\n" +
                "                    resubBox.style.display = 'none';\n" +
                "                    unsubBox.style.display = 'block';\n" +
                "                    heading.innerText = 'Unsubscribe Request';\n" +
                "                    desc.innerHTML = 'Are you sure you want to stop receiving marketing emails sent to <strong>" + recipientEmail + "</strong>?';\n" +
                "                    badge.innerText = 'Status: Active';\n" +
                "                    badge.className = 'badge badge-active';\n" +
                "                    statusMsg.className = 'status-msg msg-sub';\n" +
                "                    statusMsg.innerText = 'You have been successfully resubscribed to marketing emails!';\n" +
                "                    statusMsg.style.display = 'block';\n" +
                "                    unsubBtn.disabled = false;\n" +
                "                    unsubBtn.innerText = 'Confirm Unsubscribe';\n" +
                "                } else {\n" +
                "                    resubBtn.disabled = false;\n" +
                "                    resubBtn.innerText = 'Resubscribe to Emails';\n" +
                "                    alert('Failed to resubscribe. Please try again.');\n" +
                "                }\n" +
                "            });\n" +
                "        });\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";

        return ResponseEntity.ok(html);
    }

    @PostMapping("/u/{trackingToken}")
    public ResponseEntity<Void> handleUnsubscribePost(@PathVariable String trackingToken, HttpServletRequest request) {
        String clientIp = getClientIp(request);
        if (!rateLimitConfig.tryConsume(clientIp, RateLimitConfig.Tier.PUBLIC)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        processUnsubscribe(trackingToken);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/u/{trackingToken}/resubscribe")
    public ResponseEntity<Void> handleResubscribePost(@PathVariable String trackingToken, HttpServletRequest request) {
        String clientIp = getClientIp(request);
        if (!rateLimitConfig.tryConsume(clientIp, RateLimitConfig.Tier.PUBLIC)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        processResubscribe(trackingToken);
        return ResponseEntity.ok().build();
    }

    private void processUnsubscribe(String trackingToken) {
        Optional<EmailCampaignRecipient> optRecipient = recipientRepository.findByTrackingToken(trackingToken);
        if (optRecipient.isPresent()) {
            EmailCampaignRecipient recipient = optRecipient.get();
            LocalDateTime now = LocalDateTime.now();

            suppressionService.addSuppression(
                recipient.getTenantId(), 
                recipient.getEmail(), 
                SuppressionReason.UNSUBSCRIBED, 
                recipient.getCampaignId(), 
                null
            );

            // Log event if first time unsubscribing
            if (recipient.getUnsubscribedAt() == null) {
                recipient.setUnsubscribedAt(now);
                recipientRepository.save(recipient);

                EmailRecipientEvent event = EmailRecipientEvent.builder()
                        .tenantId(recipient.getTenantId())
                        .campaignId(recipient.getCampaignId())
                        .recipientId(recipient.getId())
                        .eventType(EmailRecipientEvent.EventType.UNSUBSCRIBED)
                        .occurredAt(now)
                        .build();
                eventRepository.save(event);
            }
        }
    }

    private void processResubscribe(String trackingToken) {
        Optional<EmailCampaignRecipient> optRecipient = recipientRepository.findByTrackingToken(trackingToken);
        if (optRecipient.isPresent()) {
            EmailCampaignRecipient recipient = optRecipient.get();
            suppressionService.removeSuppressionByEmail(recipient.getTenantId(), recipient.getEmail());
            if (recipient.getUnsubscribedAt() != null) {
                recipient.setUnsubscribedAt(null);
                recipientRepository.save(recipient);
            }
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isEmpty()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
