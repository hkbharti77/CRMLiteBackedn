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

        // Render confirmation UI — DO NOT execute state-changing suppression on GET
        String html = "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>Unsubscribe Confirmation</title>\n" +
                "    <style>\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background-color: #f9fafb; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }\n" +
                "        .container { background: white; padding: 40px; border-radius: 16px; box-shadow: 0 10px 25px rgba(0, 0, 0, 0.05); text-align: center; max-width: 440px; width: 90%; }\n" +
                "        h1 { color: #111827; font-size: 24px; margin-bottom: 12px; font-weight: 700; }\n" +
                "        p { color: #4b5563; font-size: 15px; line-height: 1.5; margin-bottom: 24px; }\n" +
                "        .btn { background-color: #ef4444; color: white; border: none; padding: 12px 28px; font-size: 15px; font-weight: 600; border-radius: 8px; cursor: pointer; transition: background-color 0.2s; }\n" +
                "        .btn:hover { background-color: #dc2626; }\n" +
                "        .success-msg { display: none; color: #059669; font-weight: 600; font-size: 16px; margin-top: 16px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <h1>Unsubscribe Request</h1>\n" +
                "        <p>Are you sure you want to stop receiving marketing emails sent to <strong>" + recipientEmail + "</strong>?</p>\n" +
                "        <form id=\"unsubForm\" action=\"/api/v1/u/" + trackingToken + "\" method=\"POST\">\n" +
                "            <button type=\"submit\" class=\"btn\">Confirm Unsubscribe</button>\n" +
                "        </form>\n" +
                "        <div id=\"successMsg\" class=\"success-msg\">You have been successfully unsubscribed.</div>\n" +
                "        <script>\n" +
                "            document.getElementById('unsubForm').addEventListener('submit', function(e) {\n" +
                "                e.preventDefault();\n" +
                "                fetch(this.action, { method: 'POST' }).then(function(res) {\n" +
                "                    if (res.ok) {\n" +
                "                        document.getElementById('unsubForm').style.display = 'none';\n" +
                "                        document.getElementById('successMsg').style.display = 'block';\n" +
                "                    }\n" +
                "                });\n" +
                "            });\n" +
                "        </script>\n" +
                "    </div>\n" +
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

    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isEmpty()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
