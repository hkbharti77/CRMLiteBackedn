package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.payments.NormalizedPaymentWebhookEvent;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.models.payments.TenantPaymentConfig;
import com.chatcrmlite.backend.repositories.payments.TenantPaymentConfigRepository;
import com.chatcrmlite.backend.services.payments.PaymentProvider;
import com.chatcrmlite.backend.services.payments.PaymentProviderFactory;
import com.chatcrmlite.backend.services.payments.PaymentWebhookProcessor;
import com.chatcrmlite.backend.services.payments.TenantPaymentConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/webhook")
@Slf4j
@RequiredArgsConstructor
public class UnifiedPaymentWebhookController {

    private final TenantPaymentConfigRepository configRepository;
    private final PaymentProviderFactory providerFactory;
    private final PaymentWebhookProcessor webhookProcessor;
    private final com.chatcrmlite.backend.repositories.CommerceOrderRepository commerceOrderRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @PostMapping("/{webhookKey}/{provider}")
    public ResponseEntity<String> handleDirectGatewayWebhook(
        @PathVariable("webhookKey") String webhookKey,
        @PathVariable("provider") String providerStr,
        @RequestBody String rawPayload,
        @RequestHeader Map<String, String> headers
    ) {
        PaymentIntegrationType providerType;
        try {
            providerType = PaymentIntegrationType.valueOf(providerStr.toUpperCase());
        } catch (Exception e) {
            log.warn("Unknown provider in webhook URL: {}", providerStr);
            return ResponseEntity.badRequest().body("Unknown provider");
        }

        String keyHash = TenantPaymentConfigService.sha256(webhookKey);
        TenantPaymentConfig config = configRepository.findByWebhookKeyHash(keyHash).orElse(null);

        if (config == null || config.getIntegrationType() != providerType) {
            log.warn("Invalid or unmapped webhook key: provider={}", providerStr);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid webhook key");
        }

        UUID tenantId = config.getTenantId();
        PaymentProvider provider = providerFactory.getProvider(providerType);

        // 1. Signature-First Verification
        boolean isValid = provider.verifyWebhookAuthenticity(tenantId, rawPayload, headers);
        if (!isValid) {
            log.error("Signature verification failed for tenant={} provider={}", tenantId, providerType);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        // 2. Parse into Normalized Event
        NormalizedPaymentWebhookEvent normalizedEvent = provider.parseWebhook(tenantId, config.getId(), rawPayload, headers);
        if (normalizedEvent == null) {
            log.info("Webhook payload ignored or empty for tenant={} provider={}", tenantId, providerType);
            return ResponseEntity.ok("Ignored");
        }

        // 3. Process Event with Idempotency and Row Locking
        try {
            webhookProcessor.processNormalizedEvent(normalizedEvent);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error processing normalized payment webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Processing error");
        }
    }


    @PostMapping("/razorpay")
    public ResponseEntity<String> handleDirectRazorpayWebhook(
        @RequestBody String rawPayload,
        @RequestHeader Map<String, String> headers
    ) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(rawPayload);
            String refId = root.path("payload").path("payment_link").path("entity").path("reference_id").asText(null);
            String linkId = root.path("payload").path("payment_link").path("entity").path("id").asText(null);

            UUID tenantId = null;
            if (refId != null) {
                try {
                    UUID orderId = UUID.fromString(refId);
                    tenantId = commerceOrderRepository.findById(orderId).map(o -> o.getTenant().getId()).orElse(null);
                } catch (Exception ignored) {}
            }
            if (tenantId == null && linkId != null) {
                tenantId = commerceOrderRepository.findByPaymentLinkId(linkId).map(o -> o.getTenant().getId()).orElse(null);
            }

            if (tenantId == null) {
                log.warn("Could not determine tenant from Razorpay direct webhook payload");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Tenant unresolved");
            }

            TenantPaymentConfig config = configRepository.findActiveByTenantIdAndType(tenantId, PaymentIntegrationType.RAZORPAY_DIRECT)
                .orElse(null);
            if (config == null) {
                log.warn("No active Razorpay configuration for tenant {}", tenantId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Config missing");
            }

            PaymentProvider provider = providerFactory.getProvider(PaymentIntegrationType.RAZORPAY_DIRECT);
            boolean isValid = provider.verifyWebhookAuthenticity(tenantId, rawPayload, headers);
            if (!isValid) {
                log.error("Signature verification failed for tenant {} direct Razorpay webhook", tenantId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
            }

            NormalizedPaymentWebhookEvent normalizedEvent = provider.parseWebhook(tenantId, config.getId(), rawPayload, headers);
            if (normalizedEvent != null) {
                webhookProcessor.processNormalizedEvent(normalizedEvent);
            }
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error processing direct Razorpay webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error");
        }
    }
}

