package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.sms.SmsDeliveryEvent;
import com.chatcrmlite.backend.models.sms.SmsMessage;
import com.chatcrmlite.backend.models.sms.SmsSuppression;
import com.chatcrmlite.backend.repositories.SmsDeliveryEventRepository;
import com.chatcrmlite.backend.repositories.SmsMessageRepository;
import com.chatcrmlite.backend.repositories.SmsSuppressionRepository;
import com.chatcrmlite.backend.services.sms.SmsProviderResolver;
import com.chatcrmlite.backend.services.sms.SmsSenderProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks/sms")
@RequiredArgsConstructor
public class SmsWebhookController {

    private final SmsDeliveryEventRepository deliveryEventRepository;
    private final SmsMessageRepository messageRepository;
    private final SmsSuppressionRepository suppressionRepository;
    private final SmsProviderResolver providerResolver;
    private final ObjectMapper objectMapper;

    @PostMapping("/{provider}")
    @Transactional
    public ResponseEntity<String> handleWebhook(
            @PathVariable("provider") String providerType,
            @RequestHeader(name = "X-Twilio-Signature", required = false) String twilioSignature,
            @RequestBody(required = false) Map<String, Object> body) {

        log.info("Received SMS Webhook for provider: {}", providerType);
        if (body == null || body.isEmpty()) {
            return ResponseEntity.ok("EMPTY_PAYLOAD");
        }

        Optional<SmsSenderProvider> providerOpt = providerResolver.resolve(providerType);
        if (providerOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("UNSUPPORTED_PROVIDER");
        }

        // Extract Provider Message ID & Event ID
        String messageId = (String) body.getOrDefault("MessageSid", body.getOrDefault("message_id", body.get("sid")));
        String eventId = (String) body.getOrDefault("SmsSid", body.getOrDefault("event_id", messageId));
        String rawStatus = (String) body.getOrDefault("MessageStatus", body.getOrDefault("status", "RECEIVED"));

        if (messageId == null) {
            return ResponseEntity.ok("NO_MESSAGE_ID");
        }

        // 1. Idempotency Check via provider_event_id
        if (eventId != null && deliveryEventRepository.findByProviderTypeAndProviderEventId(providerType.toUpperCase(), eventId).isPresent()) {
            log.info("Duplicate SMS Webhook event ignored: provider={}, eventId={}", providerType, eventId);
            return ResponseEntity.ok("DUPLICATE_EVENT_IGNORED");
        }

        // 2. Persist Delivery Event Record
        SmsDeliveryEvent event = new SmsDeliveryEvent();
        event.setProviderType(providerType.toUpperCase());
        event.setProviderMessageId(messageId);
        event.setProviderEventId(eventId);
        event.setEventType(rawStatus.toUpperCase());
        try {
            event.setRawPayload(objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            log.error("Error serializing webhook body", e);
        }
        event.setOccurredAt(LocalDateTime.now());
        deliveryEventRepository.save(event);

        // 3. Update Delivery Status in Message Ledger
        Optional<SmsMessage> msgOpt = messageRepository.findByProviderTypeAndProviderMessageId(providerType.toUpperCase(), messageId);
        if (msgOpt.isPresent()) {
            SmsMessage msg = msgOpt.get();
            String mappedStatus = mapDeliveryStatus(rawStatus);
            msg.setDeliveryStatus(mappedStatus);
            messageRepository.save(msg);
            log.info("Updated SMS delivery status for msgId={} to {}", messageId, mappedStatus);
        }

        // 4. Handle Inbound Opt-Out STOP / UNSUBSCRIBE keywords
        String bodyText = (String) body.get("Body");
        String fromPhone = (String) body.get("From");
        if (bodyText != null && fromPhone != null) {
            String trimmedUpper = bodyText.trim().toUpperCase();
            if ("STOP".equals(trimmedUpper) || "UNSUBSCRIBE".equals(trimmedUpper) || "CANCEL".equals(trimmedUpper)) {
                if (!suppressionRepository.existsByBusinessIdAndPhoneNumber("default", fromPhone)) {
                    SmsSuppression suppression = new SmsSuppression();
                    suppression.setBusinessId("default");
                    suppression.setPhoneNumber(fromPhone);
                    suppression.setReason("USER_OPT_OUT_KEYWORD_" + trimmedUpper);
                    suppression.setSource("INBOUND_SMS");
                    suppressionRepository.save(suppression);
                    log.info("Registered SMS opt-out suppression for number {}", fromPhone);
                }
            }
        }

        return ResponseEntity.ok("PROCESSED");
    }

    private String mapDeliveryStatus(String rawStatus) {
        if (rawStatus == null) return "SENT";
        switch (rawStatus.toLowerCase()) {
            case "delivered": return "DELIVERED";
            case "failed":
            case "undelivered": return "FAILED";
            case "sent": return "SENT";
            default: return rawStatus.toUpperCase();
        }
    }
}
