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
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class RazorpayDirectPaymentProvider implements PaymentProvider {

    private final TenantPaymentConfigRepository configRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.builder().baseUrl("https://api.razorpay.com").build();

    @Override
    public PaymentIntegrationType getProviderType() {
        return PaymentIntegrationType.RAZORPAY_DIRECT;
    }

    @Override
    public PaymentCapabilities getCapabilities() {
        return PaymentCapabilities.builder()
            .supportsNativeWhatsApp(false)
            .supportsPaymentLink(true)
            .supportsRefund(true)
            .supportsPartialRefund(true)
            .supportsRecurring(false)
            .build();
    }

    @Override
    public ProviderPaymentCreationResult createProviderOrder(UUID tenantId, PaymentCreationContext context) {
        TenantPaymentConfig config = configRepository.findActiveByTenantIdAndType(tenantId, PaymentIntegrationType.RAZORPAY_DIRECT)
            .orElseThrow(() -> new IllegalStateException("Razorpay Direct configuration not active for tenant: " + tenantId));

        try {
            String authHeader = "Basic " + Base64.getEncoder().encodeToString((config.getKeyId() + ":" + config.getKeySecret()).getBytes(StandardCharsets.UTF_8));

            Map<String, Object> body = new HashMap<>();
            body.put("amount", context.getAmountMinor());
            body.put("currency", context.getCurrency());
            body.put("accept_partial", false);
            body.put("reference_id", context.getOrderReference());
            body.put("description", "Payment for order #" + context.getOrderReference());

            Map<String, Object> customer = new HashMap<>();
            if (context.getCustomerName() != null) customer.put("name", context.getCustomerName());
            if (context.getCustomerWaId() != null) customer.put("contact", context.getCustomerWaId());
            body.put("customer", customer);

            Map<String, Object> notify = new HashMap<>();
            notify.put("sms", false);
            notify.put("email", false);
            body.put("notify", notify);

            String response = restClient.post()
                .uri("/v1/payment_links")
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

            JsonNode resNode = objectMapper.readTree(response);
            String providerOrderId = resNode.path("id").asText();
            String shortUrl = resNode.path("short_url").asText();

            return ProviderPaymentCreationResult.builder()
                .success(true)
                .providerOrderId(providerOrderId)
                .checkoutUrl(shortUrl)
                .rawReference(response)
                .build();
        } catch (Exception e) {
            log.error("Razorpay payment link creation failed for tenant {}: {}", tenantId, e.getMessage(), e);
            return ProviderPaymentCreationResult.builder()
                .success(false)
                .errorCode("RAZORPAY_ERROR")
                .errorMessage(e.getMessage())
                .build();
        }
    }

    @Override
    public PaymentStatusResult getPaymentStatus(UUID tenantId, String providerOrderIdOrPaymentId) {
        TenantPaymentConfig config = configRepository.findActiveByTenantIdAndType(tenantId, PaymentIntegrationType.RAZORPAY_DIRECT)
            .orElseThrow(() -> new IllegalStateException("Razorpay Direct configuration not active for tenant: " + tenantId));

        try {
            String authHeader = "Basic " + Base64.getEncoder().encodeToString((config.getKeyId() + ":" + config.getKeySecret()).getBytes(StandardCharsets.UTF_8));

            String response = restClient.get()
                .uri("/v1/payment_links/" + providerOrderIdOrPaymentId)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .body(String.class);

            JsonNode resNode = objectMapper.readTree(response);
            String statusStr = resNode.path("status").asText();

            PaymentTransactionStatus status = switch (statusStr.toLowerCase()) {
                case "paid" -> PaymentTransactionStatus.SUCCESS;
                case "cancelled", "expired" -> PaymentTransactionStatus.FAILED;
                default -> PaymentTransactionStatus.PENDING;
            };

            return PaymentStatusResult.builder()
                .status(status)
                .providerOrderId(providerOrderIdOrPaymentId)
                .rawResponse(response)
                .build();
        } catch (Exception e) {
            log.error("Failed to query Razorpay status: {}", e.getMessage(), e);
            return PaymentStatusResult.builder()
                .status(PaymentTransactionStatus.UNKNOWN)
                .failureReason(e.getMessage())
                .build();
        }
    }

    @Override
    public RefundResult refund(UUID tenantId, RefundRequest request) {
        TenantPaymentConfig config = configRepository.findActiveByTenantIdAndType(tenantId, PaymentIntegrationType.RAZORPAY_DIRECT)
            .orElseThrow(() -> new IllegalStateException("Razorpay Direct configuration not active for tenant: " + tenantId));

        try {
            String authHeader = "Basic " + Base64.getEncoder().encodeToString((config.getKeyId() + ":" + config.getKeySecret()).getBytes(StandardCharsets.UTF_8));

            Map<String, Object> body = new HashMap<>();
            body.put("amount", request.getAmountMinor());
            if (request.getReason() != null) {
                Map<String, String> notes = new HashMap<>();
                notes.put("reason", request.getReason());
                body.put("notes", notes);
            }

            String response = restClient.post()
                .uri("/v1/payments/" + request.getProviderPaymentId() + "/refund")
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
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
            log.error("Failed to refund via Razorpay: {}", e.getMessage(), e);
            return RefundResult.builder()
                .success(false)
                .status(PaymentRefundStatus.FAILED)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    @Override
    public boolean verifyWebhookAuthenticity(UUID tenantId, String rawPayload, Map<String, String> headers) {
        TenantPaymentConfig config = configRepository.findActiveByTenantIdAndType(tenantId, PaymentIntegrationType.RAZORPAY_DIRECT)
            .orElse(null);
        if (config == null || config.getWebhookSecret() == null) return false;

        String signature = headers.get("x-razorpay-signature");
        if (signature == null) return false;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(config.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.error("Razorpay webhook signature verification error: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public NormalizedPaymentWebhookEvent parseWebhook(UUID tenantId, UUID integrationId, String rawPayload, Map<String, String> headers) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String eventType = root.path("event").asText();
            JsonNode payloadNode = root.path("payload");
            JsonNode paymentNode = payloadNode.path("payment").path("entity");
            JsonNode orderNode = payloadNode.path("payment_link").path("entity");

            String providerPaymentId = paymentNode.path("id").asText(null);
            String providerOrderId = orderNode.path("id").asText(null);
            String orderReferenceId = orderNode.path("reference_id").asText(null);
            Long amountMinor = paymentNode.path("amount").asLong(0L);
            String currency = paymentNode.path("currency").asText("INR");

            PaymentTransactionStatus status = switch (eventType) {
                case "payment.captured", "payment_link.paid" -> PaymentTransactionStatus.SUCCESS;
                case "payment.failed", "payment_link.cancelled", "payment_link.expired" -> PaymentTransactionStatus.FAILED;
                default -> PaymentTransactionStatus.PENDING;
            };

            String providerEventKey = root.path("event_id").asText(providerPaymentId != null ? providerPaymentId + "_" + eventType : UUID.randomUUID().toString());

            return NormalizedPaymentWebhookEvent.builder()
                .tenantId(tenantId)
                .integrationId(integrationId)
                .provider(PaymentIntegrationType.RAZORPAY_DIRECT)
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
            log.error("Failed to parse Razorpay webhook: {}", e.getMessage(), e);
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
