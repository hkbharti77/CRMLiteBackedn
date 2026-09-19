package com.chatcrmlite.backend.dto.payments;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class PaymentCreationContext {
    UUID tenantId;
    UUID orderId;
    UUID transactionId;
    String idempotencyKey;
    String orderReference;
    String customerWaId;
    String customerName;
    Long amountMinor;
    String currency;
    String description;
    String returnUrl;
}
