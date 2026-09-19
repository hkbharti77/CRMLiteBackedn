package com.chatcrmlite.backend.services.payments.providers;

import com.chatcrmlite.backend.dto.payments.*;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.models.enums.PaymentTransactionStatus;
import com.chatcrmlite.backend.services.payments.PaymentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class MetaWhatsAppPaymentProvider implements PaymentProvider {

    private final ObjectMapper objectMapper;

    @Override
    public PaymentIntegrationType getProviderType() {
        return PaymentIntegrationType.META_WHATSAPP;
    }

    @Override
    public PaymentCapabilities getCapabilities() {
        return PaymentCapabilities.builder()
            .supportsNativeWhatsApp(true)
            .supportsPaymentLink(false)
            .supportsRefund(false)
            .supportsPartialRefund(false)
            .supportsRecurring(false)
            .build();
    }

    @Override
    public ProviderPaymentCreationResult createProviderOrder(UUID tenantId, PaymentCreationContext context) {
        // Meta Native does not require pre-creating an external order over a gateway API;
        // the order is initiated directly on WhatsApp via order_details interactive message.
        return ProviderPaymentCreationResult.builder()
            .success(true)
            .providerOrderId(context.getOrderReference())
            .checkoutUrl(null)
            .rawReference("META_NATIVE_READY")
            .build();
    }

    @Override
    public PaymentStatusResult getPaymentStatus(UUID tenantId, String providerOrderIdOrPaymentId) {
        return PaymentStatusResult.builder()
            .status(PaymentTransactionStatus.PENDING)
            .providerPaymentId(providerOrderIdOrPaymentId)
            .build();
    }

    @Override
    public RefundResult refund(UUID tenantId, RefundRequest request) {
        return RefundResult.builder()
            .success(false)
            .errorMessage("Refunds for Meta Native UPI payments must be processed via the connected Merchant Gateway.")
            .build();
    }

    @Override
    public boolean verifyWebhookAuthenticity(UUID tenantId, String rawPayload, Map<String, String> headers) {
        // Meta WhatsApp Webhook authenticity is verified at the App Secret level by WhatsAppWebhookController.
        return true;
    }

    @Override
    public NormalizedPaymentWebhookEvent parseWebhook(UUID tenantId, UUID integrationId, String rawPayload, Map<String, String> headers) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode entryNode = root.path("entry");
            if (!entryNode.isArray() || entryNode.isEmpty()) {
                return null;
            }

            JsonNode changes = entryNode.get(0).path("changes");
            if (!changes.isArray() || changes.isEmpty()) {
                return null;
            }

            JsonNode value = changes.get(0).path("value");
            
            // Check 1: `statuses[]` where status.type == 'payment'
            JsonNode statuses = value.path("statuses");
            if (statuses.isArray() && !statuses.isEmpty()) {
                JsonNode statusNode = statuses.get(0);
                JsonNode paymentNode = statusNode.path("payment");
                if (!paymentNode.isMissingNode()) {
                    String refId = paymentNode.path("reference_id").asText(null);
                    String paymentStatus = paymentNode.path("status").asText("pending");
                    String paymentId = paymentNode.path("id").asText(null);
                    String providerEventKey = statusNode.path("id").asText(UUID.randomUUID().toString()) + "_" + paymentStatus;

                    PaymentTransactionStatus txStatus = switch (paymentStatus.toLowerCase()) {
                        case "captured", "success", "paid" -> PaymentTransactionStatus.SUCCESS;
                        case "failed", "canceled" -> PaymentTransactionStatus.FAILED;
                        default -> PaymentTransactionStatus.PENDING;
                    };

                    return NormalizedPaymentWebhookEvent.builder()
                        .tenantId(tenantId)
                        .integrationId(integrationId)
                        .provider(PaymentIntegrationType.META_WHATSAPP)
                        .providerEventKey(providerEventKey)
                        .eventType("payment_status_update")
                        .orderReferenceId(refId)
                        .providerPaymentId(paymentId)
                        .providerOrderId(refId)
                        .transactionStatus(txStatus)
                        .occurredAt(Instant.now())
                        .rawPayload(root)
                        .rawPayloadString(rawPayload)
                        .payloadHash(hashPayload(rawPayload))
                        .build();
                }
            }

            // Check 2: `messages[]` where type == 'order'
            JsonNode messages = value.path("messages");
            if (messages.isArray() && !messages.isEmpty()) {
                JsonNode messageNode = messages.get(0);
                if ("order".equalsIgnoreCase(messageNode.path("type").asText())) {
                    JsonNode orderNode = messageNode.path("order");
                    String refId = orderNode.path("reference_id").asText(null);
                    String paymentStatus = orderNode.path("payment_status").asText("pending");
                    String providerEventKey = messageNode.path("id").asText(UUID.randomUUID().toString());

                    PaymentTransactionStatus txStatus = switch (paymentStatus.toLowerCase()) {
                        case "captured", "success", "paid" -> PaymentTransactionStatus.SUCCESS;
                        case "failed" -> PaymentTransactionStatus.FAILED;
                        default -> PaymentTransactionStatus.PENDING;
                    };

                    return NormalizedPaymentWebhookEvent.builder()
                        .tenantId(tenantId)
                        .integrationId(integrationId)
                        .provider(PaymentIntegrationType.META_WHATSAPP)
                        .providerEventKey(providerEventKey)
                        .eventType("message_order")
                        .orderReferenceId(refId)
                        .providerOrderId(refId)
                        .transactionStatus(txStatus)
                        .occurredAt(Instant.now())
                        .rawPayload(root)
                        .rawPayloadString(rawPayload)
                        .payloadHash(hashPayload(rawPayload))
                        .build();
                }
            }

            return null;
        } catch (Exception e) {
            log.error("Failed to parse Meta WhatsApp payment webhook: {}", e.getMessage(), e);
            return null;
        }
    }

    private String hashPayload(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
