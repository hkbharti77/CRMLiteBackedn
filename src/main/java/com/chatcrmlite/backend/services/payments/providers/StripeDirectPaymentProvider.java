package com.chatcrmlite.backend.services.payments.providers;

import com.chatcrmlite.backend.dto.payments.*;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.models.enums.PaymentRefundStatus;
import com.chatcrmlite.backend.models.enums.PaymentTransactionStatus;
import com.chatcrmlite.backend.models.payments.TenantPaymentConfig;
import com.chatcrmlite.backend.repositories.payments.TenantPaymentConfigRepository;
import com.chatcrmlite.backend.services.payments.PaymentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StripeDirectPaymentProvider implements PaymentProvider {

    private final TenantPaymentConfigRepository configRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.builder().baseUrl("https://api.stripe.com").build();

    @Override
    public PaymentIntegrationType getProviderType() {
        return PaymentIntegrationType.STRIPE_DIRECT;
    }

    @Override
    public PaymentCapabilities getCapabilities() {
        return PaymentCapabilities.builder()
            .supportsNativeWhatsApp(false)
            .supportsPaymentLink(true)
            .supportsRefund(true)
            .supportsPartialRefund(true)
            .supportsRecurring(true)
            .build();
    }

    @Override
    public ProviderPaymentCreationResult createProviderOrder(UUID tenantId, PaymentCreationContext context) {
        TenantPaymentConfig config = configRepository.findActiveByTenantIdAndType(tenantId, PaymentIntegrationType.STRIPE_DIRECT)
            .orElseThrow(() -> new IllegalStateException("Stripe Direct configuration not active for tenant: " + tenantId));

        try {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("payment_method_types[0]", "card");
            formData.add("mode", "payment");
            formData.add("client_reference_id", context.getOrderReference());
            formData.add("success_url", context.getReturnUrl() != null ? context.getReturnUrl() : "https://chatcrm.local/payment/success");
            formData.add("cancel_url", "https://chatcrm.local/payment/cancel");
            formData.add("line_items[0][price_data][currency]", context.getCurrency().toLowerCase());
            formData.add("line_items[0][price_data][unit_amount]", String.valueOf(context.getAmountMinor()));
            formData.add("line_items[0][price_data][product_data][name]", "Order #" + context.getOrderReference());
            formData.add("line_items[0][quantity]", "1");

            String response = restClient.post()
                .uri("/v1/checkout/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getKeySecret())
                .header("Idempotency-Key", context.getIdempotencyKey())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formData)
                .retrieve()
                .body(String.class);

            JsonNode resNode = objectMapper.readTree(response);
            String sessionId = resNode.path("id").asText();
            String sessionUrl = resNode.path("url").asText();

            return ProviderPaymentCreationResult.builder()
                .success(true)
                .providerOrderId(sessionId)
                .checkoutUrl(sessionUrl)
                .rawReference(response)
                .build();
        } catch (Exception e) {
            log.error("Stripe Checkout Session creation failed: {}", e.getMessage(), e);
            return ProviderPaymentCreationResult.builder()
                .success(false)
                .errorCode("STRIPE_ERROR")
                .errorMessage(e.getMessage())
                .build();
        }
    }

    @Override
    public PaymentStatusResult getPaymentStatus(UUID tenantId, String providerOrderIdOrPaymentId) {
        return PaymentStatusResult.builder()
            .status(PaymentTransactionStatus.PENDING)
            .providerOrderId(providerOrderIdOrPaymentId)
            .build();
    }

    @Override
    public RefundResult refund(UUID tenantId, RefundRequest request) {
        TenantPaymentConfig config = configRepository.findActiveByTenantIdAndType(tenantId, PaymentIntegrationType.STRIPE_DIRECT)
            .orElseThrow(() -> new IllegalStateException("Stripe Direct configuration not active for tenant: " + tenantId));

        try {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("payment_intent", request.getProviderPaymentId());
            formData.add("amount", String.valueOf(request.getAmountMinor()));

            String response = restClient.post()
                .uri("/v1/refunds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getKeySecret())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formData)
                .retrieve()
                .body(String.class);

            JsonNode resNode = objectMapper.readTree(response);
            String refundId = resNode.path("id").asText();

            return RefundResult.builder()
                .success(true)
                .status(PaymentRefundStatus.PROCESSED)
                .providerRefundId(refundId)
                .amountMinor(request.getAmountMinor())
                .processedAt(Instant.now())
                .rawReference(response)
                .build();
        } catch (Exception e) {
            log.error("Stripe refund failed: {}", e.getMessage(), e);
            return RefundResult.builder()
                .success(false)
                .status(PaymentRefundStatus.FAILED)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    @Override
    public boolean verifyWebhookAuthenticity(UUID tenantId, String rawPayload, Map<String, String> headers) {
        return true;
    }

    @Override
    public NormalizedPaymentWebhookEvent parseWebhook(UUID tenantId, UUID integrationId, String rawPayload, Map<String, String> headers) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String eventType = root.path("type").asText();
            JsonNode dataObj = root.path("data").path("object");

            String providerPaymentId = dataObj.path("payment_intent").asText(null);
            String providerOrderId = dataObj.path("id").asText(null);
            String orderReferenceId = dataObj.path("client_reference_id").asText(null);
            Long amountMinor = dataObj.path("amount_total").asLong(0L);
            String currency = dataObj.path("currency").asText("INR").toUpperCase();

            PaymentTransactionStatus status = "checkout.session.completed".equalsIgnoreCase(eventType)
                ? PaymentTransactionStatus.SUCCESS
                : PaymentTransactionStatus.PENDING;

            String providerEventKey = root.path("id").asText(UUID.randomUUID().toString());

            return NormalizedPaymentWebhookEvent.builder()
                .tenantId(tenantId)
                .integrationId(integrationId)
                .provider(PaymentIntegrationType.STRIPE_DIRECT)
                .providerEventKey(providerEventKey)
                .eventType(eventType)
                .orderReferenceId(orderReferenceId)
                .providerPaymentId(providerPaymentId)
                .providerOrderId(providerOrderId)
                .amountMinor(amountMinor)
                .currency(currency)
                .transactionStatus(status)
                .occurredAt(Instant.now())
                .rawPayload(root)
                .rawPayloadString(rawPayload)
                .payloadHash(hashPayload(rawPayload))
                .build();
        } catch (Exception e) {
            log.error("Failed to parse Stripe webhook: {}", e.getMessage(), e);
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
