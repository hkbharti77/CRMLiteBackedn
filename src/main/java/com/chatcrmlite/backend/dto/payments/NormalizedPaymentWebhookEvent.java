package com.chatcrmlite.backend.dto.payments;

import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.models.enums.PaymentTransactionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class NormalizedPaymentWebhookEvent {
    UUID tenantId;
    UUID integrationId;
    PaymentIntegrationType provider;
    String providerEventKey;
    String eventType;
    String orderReferenceId;
    String providerPaymentId;
    String providerOrderId;
    Long amountMinor;
    String currency;
    PaymentTransactionStatus transactionStatus;
    Instant occurredAt;
    JsonNode rawPayload;
    String rawPayloadString;
    String payloadHash;
}
