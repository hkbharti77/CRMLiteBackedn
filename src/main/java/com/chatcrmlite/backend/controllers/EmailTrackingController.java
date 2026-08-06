package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.models.email.EmailRecipientEvent;
import com.chatcrmlite.backend.models.email.EmailTrackedLink;
import com.chatcrmlite.backend.models.email.EmailSuppressionList.SuppressionReason;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailRecipientEventRepository;
import com.chatcrmlite.backend.repositories.email.EmailTrackedLinkRepository;
import com.chatcrmlite.backend.services.email.EmailSuppressionService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StreamUtils;

import java.io.IOException;
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

    private static final byte[] PIXEL_BYTES = new byte[]{
            71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, 0, 0, 0, 0, 0, -1, -1, -1, 33, -9, 4, 1, 0, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59
    };

    @GetMapping("/t/o/{trackingToken}.png")
    public ResponseEntity<byte[]> trackOpen(@PathVariable String trackingToken) {
        Optional<EmailCampaignRecipient> optRecipient = recipientRepository.findByTrackingToken(trackingToken);

        if (optRecipient.isPresent()) {
            EmailCampaignRecipient recipient = optRecipient.get();
            
            // Create raw event
            EmailRecipientEvent event = EmailRecipientEvent.builder()
                    .tenantId(recipient.getTenantId())
                    .campaignId(recipient.getCampaignId())
                    .recipientId(recipient.getId())
                    .eventType(EmailRecipientEvent.EventType.OPENED)
                    .build();
            eventRepository.save(event);

            // Update recipient first_opened_at idempotently
            if (recipient.getFirstOpenedAt() == null) {
                recipient.setFirstOpenedAt(LocalDateTime.now());
                recipientRepository.save(recipient);
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_GIF);
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        headers.setPragma("no-cache");
        headers.setExpires(0L);

        return new ResponseEntity<>(PIXEL_BYTES, headers, HttpStatus.OK);
    }

    @GetMapping("/t/c/{trackingToken}")
    public ResponseEntity<Void> trackClick(@PathVariable String trackingToken, @RequestParam("l") String linkToken) {
        Optional<EmailCampaignRecipient> optRecipient = recipientRepository.findByTrackingToken(trackingToken);
        Optional<EmailTrackedLink> optLink = linkRepository.findByLinkToken(linkToken);

        if (optRecipient.isPresent() && optLink.isPresent()) {
            EmailCampaignRecipient recipient = optRecipient.get();
            EmailTrackedLink link = optLink.get();

            // Verify the link belongs to this campaign
            if (recipient.getCampaignId().equals(link.getCampaignId())) {
                // Create raw event
                EmailRecipientEvent event = EmailRecipientEvent.builder()
                        .tenantId(recipient.getTenantId())
                        .campaignId(recipient.getCampaignId())
                        .recipientId(recipient.getId())
                        .eventType(EmailRecipientEvent.EventType.CLICKED)
                        .linkUrl(link.getDestinationUrl())
                        .build();
                eventRepository.save(event);

                // Update recipient first_clicked_at idempotently
                if (recipient.getFirstClickedAt() == null) {
                    recipient.setFirstClickedAt(LocalDateTime.now());
                    recipientRepository.save(recipient);
                }
                
                HttpHeaders headers = new HttpHeaders();
                headers.setLocation(URI.create(link.getDestinationUrl()));
                return new ResponseEntity<>(headers, HttpStatus.FOUND);
            }
        }
        
        // Fallback if missing or invalid
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @GetMapping(value = "/u/{trackingToken}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> handleUnsubscribeGet(@PathVariable String trackingToken) {
        processUnsubscribe(trackingToken);
        
        String html = "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>Unsubscribed Successfully</title>\n" +
                "    <style>\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f9fafb; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }\n" +
                "        .container { background: white; padding: 48px 40px; border-radius: 16px; box-shadow: 0 10px 25px rgba(0, 0, 0, 0.05); text-align: center; max-width: 420px; width: 90%; }\n" +
                "        .icon { width: 72px; height: 72px; margin-bottom: 24px; color: #10b981; background: #d1fae5; border-radius: 50%; padding: 16px; box-sizing: border-box; display: inline-block; }\n" +
                "        h1 { color: #111827; font-size: 26px; margin-bottom: 16px; font-weight: 700; }\n" +
                "        p { color: #6b7280; font-size: 16px; line-height: 1.6; margin-bottom: 0; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <svg class=\"icon\" fill=\"none\" stroke=\"currentColor\" viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\">\n" +
                "            <path stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-width=\"2.5\" d=\"M5 13l4 4L19 7\"></path>\n" +
                "        </svg>\n" +
                "        <h1>Unsubscribed</h1>\n" +
                "        <p>You've been successfully removed from this mailing list. You will no longer receive these emails.</p>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
                
        return ResponseEntity.ok(html);
    }

    @PostMapping("/u/{trackingToken}")
    public ResponseEntity<Void> handleUnsubscribePost(@PathVariable String trackingToken) {
        processUnsubscribe(trackingToken);
        return ResponseEntity.ok().build();
    }

    private void processUnsubscribe(String trackingToken) {
        Optional<EmailCampaignRecipient> optRecipient = recipientRepository.findByTrackingToken(trackingToken);
        if (optRecipient.isPresent()) {
            EmailCampaignRecipient recipient = optRecipient.get();
            
            suppressionService.addSuppression(
                recipient.getTenantId(), 
                recipient.getEmail(), 
                SuppressionReason.UNSUBSCRIBED, 
                recipient.getCampaignId(), 
                null
            );

            // Log event if first time unsubscribing
            if (recipient.getUnsubscribedAt() == null) {
                recipient.setUnsubscribedAt(LocalDateTime.now());
                recipientRepository.save(recipient);

                EmailRecipientEvent event = EmailRecipientEvent.builder()
                        .tenantId(recipient.getTenantId())
                        .campaignId(recipient.getCampaignId())
                        .recipientId(recipient.getId())
                        .eventType(EmailRecipientEvent.EventType.UNSUBSCRIBED)
                        .build();
                eventRepository.save(event);
            }
        }
    }
}
