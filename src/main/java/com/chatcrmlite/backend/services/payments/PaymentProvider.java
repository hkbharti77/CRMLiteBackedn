package com.chatcrmlite.backend.services.payments;

import com.chatcrmlite.backend.dto.payments.*;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;

import java.util.Map;
import java.util.UUID;

public interface PaymentProvider {

    PaymentIntegrationType getProviderType();

    PaymentCapabilities getCapabilities();

    ProviderPaymentCreationResult createProviderOrder(UUID tenantId, PaymentCreationContext context);

    PaymentStatusResult getPaymentStatus(UUID tenantId, String providerOrderIdOrPaymentId);

    RefundResult refund(UUID tenantId, RefundRequest request);

    boolean verifyWebhookAuthenticity(UUID tenantId, String rawPayload, Map<String, String> headers);

    NormalizedPaymentWebhookEvent parseWebhook(UUID tenantId, UUID integrationId, String rawPayload, Map<String, String> headers);
}
