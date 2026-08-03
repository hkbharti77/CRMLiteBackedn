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

    @GetMapping("/u/{trackingToken}")
    public ResponseEntity<String> handleUnsubscribeGet(@PathVariable String trackingToken) {
        processUnsubscribe(trackingToken);
        return ResponseEntity.ok("You have been successfully unsubscribed.");
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
