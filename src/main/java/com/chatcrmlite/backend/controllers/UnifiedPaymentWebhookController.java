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
}
