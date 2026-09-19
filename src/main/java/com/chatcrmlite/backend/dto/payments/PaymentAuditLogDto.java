package com.chatcrmlite.backend.dto.payments;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentAuditLogDto {
    private UUID id;
    private UUID orderId;
    private UUID transactionId;
    private UUID refundId;
    private String actorType;
    private String actorId;
    private String action;
    private String oldStatus;
    private String newStatus;
    private Map<String, Object> metadata;
    private Instant createdAt;
}
