package com.chatcrmlite.backend.dto.payments;

import com.chatcrmlite.backend.models.enums.PaymentRefundStatus;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class RefundResult {
    boolean success;
    PaymentRefundStatus status;
    String providerRefundId;
    Long amountMinor;
    Instant processedAt;
    String rawReference;
    String errorMessage;
}
