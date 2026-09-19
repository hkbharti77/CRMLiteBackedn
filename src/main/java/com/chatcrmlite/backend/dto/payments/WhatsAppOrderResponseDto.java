package com.chatcrmlite.backend.dto.payments;

import com.chatcrmlite.backend.models.enums.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppOrderResponseDto {

    private UUID id;
    private String referenceId;
    private String externalReferenceId;
    private UUID customerId;
    private String customerWaId;
    private String customerName;
    private String currency;
    
    private Long subtotalMinor;
    private Long discountMinor;
    private Long taxMinor;
    private Long shippingMinor;
    private Long totalMinor;
    
    private WhatsAppOrderStatus orderStatus;
    private WhatsAppOrderPaymentStatus paymentStatus;
    private WhatsAppOrderFulfillmentStatus fulfillmentStatus;
    
    private PaymentMode preferredPaymentMode;
    private PaymentIntegrationType preferredPaymentProvider;
    
    private List<WhatsAppOrderItemDto> items;
    private List<PaymentTransactionDto> transactions;
    private List<PaymentRefundDto> refunds;
    private List<PaymentAuditLogDto> auditLogs;
    
    private Instant createdAt;
    private Instant updatedAt;
    private Instant paidAt;
    private Instant expiredAt;
    private Instant cancelledAt;
}
