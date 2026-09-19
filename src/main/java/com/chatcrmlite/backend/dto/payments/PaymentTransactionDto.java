package com.chatcrmlite.backend.dto.payments;

import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.models.enums.PaymentMode;
import com.chatcrmlite.backend.models.enums.PaymentTransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentTransactionDto {
    private UUID id;
    private PaymentIntegrationType provider;
    private PaymentMode paymentMode;
    private String idempotencyKey;
    private String providerOrderId;
    private String providerPaymentId;
    private String providerTransactionId;
    private String checkoutUrl;
    private Long amountMinor;
    private String currency;
    private PaymentTransactionStatus status;
    private String failureCode;
    private String failureReason;
    private Instant createdAt;
    private Instant paidAt;
}
