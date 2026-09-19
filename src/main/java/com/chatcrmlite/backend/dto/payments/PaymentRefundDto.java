package com.chatcrmlite.backend.dto.payments;

import com.chatcrmlite.backend.models.enums.PaymentRefundStatus;
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
public class PaymentRefundDto {
    private UUID id;
    private UUID transactionId;
    private String providerRefundId;
    private Long amountMinor;
    private String currency;
    private PaymentRefundStatus status;
    private String reason;
    private Instant createdAt;
    private Instant processedAt;
}
