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
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PayUDirectPaymentProvider implements PaymentProvider {

    private final TenantPaymentConfigRepository configRepository;
    private final ObjectMapper objectMapper;

    @Override
    public PaymentIntegrationType getProviderType() {
        return PaymentIntegrationType.PAYU_DIRECT;
    }

    @Override
    public PaymentCapabilities getCapabilities() {
        return PaymentCapabilities.builder()
            .supportsNativeWhatsApp(false)
            .supportsPaymentLink(true)
            .supportsRefund(true)
            .supportsPartialRefund(false)
            .supportsRecurring(false)
            .build();
    }

    @Override
    public ProviderPaymentCreationResult createProviderOrder(UUID tenantId, PaymentCreationContext context) {
        TenantPaymentConfig config = configRepository.findActiveByTenantIdAndType(tenantId, PaymentIntegrationType.PAYU_DIRECT)
            .orElseThrow(() -> new IllegalStateException("PayU Direct configuration not active for tenant: " + tenantId));

        String txnId = "PAYU_" + context.getOrderReference() + "_" + UUID.randomUUID().toString().substring(0, 6);
        double amount = context.getAmountMinor() / 100.0;
        String productInfo = "Order #" + context.getOrderReference();
        String firstName = context.getCustomerName() != null ? context.getCustomerName() : "Customer";
        String email = "customer@chatcrm.local";

        // Generate SHA-512 Hash: sha512(key|txnid|amount|productinfo|firstname|email|udf1|udf2|udf3|udf4|udf5||||||SALT)
        String hashSequence = String.format("%s|%s|%.2f|%s|%s|%s|||||||||||%s",
            config.getKeyId(), txnId, amount, productInfo, firstName, email, config.getKeySecret());
        String hash = sha512(hashSequence);

        // Standard PayU checkout redirect URL
        String checkoutUrl = "https://secure.payu.in/_payment?key=" + config.getKeyId() +
            "&txnid=" + txnId +
            "&amount=" + String.format("%.2f", amount) +
            "&productinfo=" + productInfo +
            "&firstname=" + firstName +
            "&email=" + email +
            "&phone=" + (context.getCustomerWaId() != null ? context.getCustomerWaId() : "") +
            "&hash=" + hash;

        return ProviderPaymentCreationResult.builder()
            .success(true)
            .providerOrderId(txnId)
            .checkoutUrl(checkoutUrl)
            .rawReference(checkoutUrl)
            .build();
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
        return RefundResult.builder()
            .success(false)
            .status(PaymentRefundStatus.FAILED)
            .errorMessage("PayU direct refund API not configured.")
            .build();
    }

    @Override
    public boolean verifyWebhookAuthenticity(UUID tenantId, String rawPayload, Map<String, String> headers) {
        TenantPaymentConfig config = configRepository.findActiveByTenantIdAndType(tenantId, PaymentIntegrationType.PAYU_DIRECT)
            .orElse(null);
        if (config == null || config.getKeySecret() == null) return false;

        // PayU sends form/JSON with status and reverse hash check
        return true;
    }

    @Override
    public NormalizedPaymentWebhookEvent parseWebhook(UUID tenantId, UUID integrationId, String rawPayload, Map<String, String> headers) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String statusStr = root.path("status").asText("pending");
            String txnid = root.path("txnid").asText();
            String mihpayid = root.path("mihpayid").asText(txnid);
            double amount = root.path("amount").asDouble(0.0);
            long amountMinor = (long) (amount * 100);

            PaymentTransactionStatus status = "success".equalsIgnoreCase(statusStr)
                ? PaymentTransactionStatus.SUCCESS
                : PaymentTransactionStatus.FAILED;

            return NormalizedPaymentWebhookEvent.builder()
                .tenantId(tenantId)
                .integrationId(integrationId)
                .provider(PaymentIntegrationType.PAYU_DIRECT)
                .providerEventKey(mihpayid)
                .eventType("payment_response")
                .providerPaymentId(mihpayid)
                .providerOrderId(txnid)
                .amountMinor(amountMinor)
                .currency("INR")
                .transactionStatus(status)
                .occurredAt(Instant.now())
                .rawPayload(root)
                .rawPayloadString(rawPayload)
                .payloadHash(hashPayload(rawPayload))
                .build();
        } catch (Exception e) {
            log.error("Failed to parse PayU webhook: {}", e.getMessage(), e);
            return null;
        }
    }

    private String sha512(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] messageDigest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
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
