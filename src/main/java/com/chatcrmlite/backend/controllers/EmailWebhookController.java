package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.models.email.EmailProviderEvent;
import com.chatcrmlite.backend.models.email.EmailSuppressionList.SuppressionReason;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailProviderEventRepository;
import com.chatcrmlite.backend.services.email.EmailSuppressionService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/webhooks/email")
@RequiredArgsConstructor
@Slf4j
public class EmailWebhookController {

    private final EmailProviderEventRepository providerEventRepository;
    private final EmailCampaignRecipientRepository recipientRepository;
    private final EmailSuppressionService suppressionService;

    @org.springframework.beans.factory.annotation.Value("${email.webhook.secret:}")
    private String configuredWebhookSecret;

    @PostMapping("/generic")
    public ResponseEntity<Void> handleGenericWebhook(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String webhookSecret,
            @RequestHeader(value = "X-Email-Webhook-Secret", required = false) String emailWebhookSecret,
            @RequestBody JsonNode payload) {
        
        // 0. Cryptographic / Shared-Secret Authentication
        String incomingSecret = (webhookSecret != null && !webhookSecret.isBlank()) ? webhookSecret : emailWebhookSecret;
        if (configuredWebhookSecret == null || configuredWebhookSecret.isBlank()) {
            log.error("[EmailWebhook] Email webhook secret is not configured in environment. Rejecting webhook request.");
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }

        if (incomingSecret == null || incomingSecret.isBlank()) {
            log.warn("[EmailWebhook] Missing webhook secret header on /generic");
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }

        boolean isValidSecret = java.security.MessageDigest.isEqual(
                incomingSecret.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                configuredWebhookSecret.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        if (!isValidSecret) {
            log.warn("[EmailWebhook] Invalid webhook secret provided on /generic");
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String eventId = payload.path("event_id").asText(null);
            String provider = payload.path("provider").asText("generic");
            String trackingToken = payload.path("tracking_token").asText(null);
            String eventType = payload.path("event_type").asText(null); // delivered, bounced, complained

            if (eventId == null || trackingToken == null || eventType == null) {
                log.warn("[Webhook] Missing required fields in generic webhook payload");
                return ResponseEntity.badRequest().build();
            }

            // 1. Check Idempotency
            if (providerEventRepository.existsByProviderAndProviderEventId(provider, eventId)) {
                log.info("[Webhook] Duplicate event ignored: {}", eventId);
                return ResponseEntity.ok().build(); // Already processed
            }

            Optional<EmailCampaignRecipient> optRecipient = recipientRepository.findByTrackingToken(trackingToken);
            if (optRecipient.isEmpty()) {
                log.warn("[Webhook] Recipient not found for token: {}", trackingToken);
                return ResponseEntity.notFound().build();
            }

            EmailCampaignRecipient recipient = optRecipient.get();

            // 2. Save Provider Event
            EmailProviderEvent event = EmailProviderEvent.builder()
                    .tenantId(recipient.getTenantId())
                    .provider(provider)
                    .providerEventId(eventId)
                    .eventType(eventType)
                    .rawPayload(payload.toString())
                    .build();
            providerEventRepository.save(event);

            // 3. Process Event
            switch (eventType.toLowerCase()) {
                case "delivered":
                    if (recipient.getDeliveryStatus() == EmailCampaignRecipient.DeliveryStatus.PENDING ||
                        recipient.getDeliveryStatus() == EmailCampaignRecipient.DeliveryStatus.SENT) {
                        recipient.setDeliveryStatus(EmailCampaignRecipient.DeliveryStatus.DELIVERED);
                        recipient.setDeliveredAt(LocalDateTime.now());
                        recipientRepository.save(recipient);
                    }
                    break;
                case "bounced":
                    recipient.setDeliveryStatus(EmailCampaignRecipient.DeliveryStatus.BOUNCED);
                    recipient.setFailedAt(LocalDateTime.now());
                    recipient.setFailureMessage("Bounced via webhook");
                    recipientRepository.save(recipient);
                    
                    suppressionService.addSuppression(
                            recipient.getTenantId(), 
                            recipient.getEmail(), 
                            SuppressionReason.HARD_BOUNCE, 
                            recipient.getCampaignId(), 
                            null
                    );
                    break;
                case "complained":
                    recipient.setDeliveryStatus(EmailCampaignRecipient.DeliveryStatus.FAILED);
                    recipient.setFailedAt(LocalDateTime.now());
                    recipient.setFailureMessage("Complained via webhook");
                    recipientRepository.save(recipient);
                    
                    suppressionService.addSuppression(
                            recipient.getTenantId(), 
                            recipient.getEmail(), 
                            SuppressionReason.COMPLAINT, 
                            recipient.getCampaignId(), 
                            null
                    );
                    break;
                default:
                    log.info("[Webhook] Unhandled event type: {}", eventType);
                    break;
            }

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("[Webhook] Error processing generic webhook", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
