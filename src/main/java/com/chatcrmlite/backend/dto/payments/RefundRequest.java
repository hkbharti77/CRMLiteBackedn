package com.chatcrmlite.backend.dto.payments;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class RefundRequest {
    UUID tenantId;
    UUID orderId;
    UUID transactionId;
    String providerPaymentId;
    Long amountMinor;
    String currency;
    String reason;
}
