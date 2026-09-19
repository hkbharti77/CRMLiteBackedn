package com.chatcrmlite.backend.dto.payments;

import com.chatcrmlite.backend.models.enums.PaymentTransactionStatus;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class PaymentStatusResult {
    PaymentTransactionStatus status;
    String providerPaymentId;
    String providerOrderId;
    Long amountMinor;
    String currency;
    Instant paidAt;
    String rawResponse;
    String failureCode;
    String failureReason;
}
