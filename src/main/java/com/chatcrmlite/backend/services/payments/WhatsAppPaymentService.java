package com.chatcrmlite.backend.services.payments;

import com.chatcrmlite.backend.dto.payments.*;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.enums.*;
import com.chatcrmlite.backend.models.payments.*;
import com.chatcrmlite.backend.repositories.payments.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class WhatsAppPaymentService {

    private final PaymentOrchestrator paymentOrchestrator;
    private final WhatsAppOrderRepository orderRepository;
    private final WhatsAppOrderItemRepository itemRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentAuditLogRepository auditLogRepository;
    private final PaymentOutboxEventRepository outboxRepository;
    private final TenantPaymentConfigRepository configRepository;
    private final PaymentProviderFactory providerFactory;
    private final ObjectMapper objectMapper;

    @Transactional
    public WhatsAppOrderResponseDto createAndSendPayment(UUID tenantId, PaymentRequestDto dto, String actorId) {
        WhatsAppOrder order = paymentOrchestrator.createPaymentOrder(tenantId, dto, actorId, "USER");
        return mapToDto(order);
    }

    @Transactional(readOnly = true)
    public WhatsAppOrderResponseDto getOrderByReference(UUID tenantId, String referenceId) {
        WhatsAppOrder order = orderRepository.findByReferenceIdAndTenantId(referenceId, tenantId)
            .orElseThrow(() -> new NoSuchElementException("Order not found: " + referenceId));
        return mapToDto(order);
    }

    @Transactional(readOnly = true)
    public WhatsAppOrderResponseDto getOrderById(UUID tenantId, UUID orderId) {
        WhatsAppOrder order = orderRepository.findByIdAndTenantId(orderId, tenantId)
            .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));
        return mapToDto(order);
    }

    @Transactional(readOnly = true)
    public Page<WhatsAppOrderResponseDto> listOrders(UUID tenantId, WhatsAppOrderPaymentStatus status, Pageable pageable) {
        Page<WhatsAppOrder> orders = (status != null)
            ? orderRepository.findAllByTenantIdAndPaymentStatus(tenantId, status, pageable)
            : orderRepository.findAllByTenantId(tenantId, pageable);
        return orders.map(this::mapToDto);
    }

    @Transactional
    public WhatsAppOrderResponseDto resendPayment(UUID tenantId, UUID orderId, String actorId) {
        WhatsAppOrder order = orderRepository.findByIdAndTenantIdForUpdate(orderId, tenantId)
            .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        if (order.getPaymentStatus() == WhatsAppOrderPaymentStatus.PAID || order.getPaymentStatus() == WhatsAppOrderPaymentStatus.REFUNDED) {
            throw new IllegalStateException("Cannot resend payment for an already " + order.getPaymentStatus() + " order");
        }

        List<PaymentTransaction> transactions = transactionRepository.findAllByOrderIdAndTenantId(orderId, tenantId);
        PaymentTransaction latestTx = transactions.isEmpty() ? null : transactions.get(0);

        TenantPaymentConfig config = latestTx != null && latestTx.getPaymentIntegration() != null
            ? latestTx.getPaymentIntegration()
            : configRepository.findAllByTenantIdAndStatus(tenantId, PaymentIntegrationStatus.ACTIVE).get(0);

        // Case 1: Active direct payment link -> re-use existing checkout URL
        if (latestTx != null && latestTx.getCheckoutUrl() != null && latestTx.getStatus() == PaymentTransactionStatus.PENDING) {
            PaymentOutboxEvent resendOutbox = PaymentOutboxEvent.builder()
                .order(order)
                .transaction(latestTx)
                .paymentIntegration(config)
                .eventType(PaymentOutboxEventType.WHATSAPP_PAYMENT_MESSAGE_SEND)
                .payload(writeJson(Map.of("orderId", order.getId().toString(), "transactionId", latestTx.getId().toString())))
                .status(PaymentOutboxStatus.PENDING)
                .build();
            resendOutbox.setTenantId(tenantId);
            outboxRepository.save(resendOutbox);

            log.info("Enqueued resend for existing valid transaction {} on order ref={}", latestTx.getId(), order.getReferenceId());
        }
        // Case 2: Meta Native or Expired/Failed Direct Payment -> Generate fresh transaction attempt
        else {
            String idempotencyKey = "TX_" + order.getId() + "_" + UUID.randomUUID().toString().substring(0, 8);
            PaymentTransaction newTx = PaymentTransaction.builder()
                .order(order)
                .paymentIntegration(config)
                .provider(order.getPreferredPaymentProvider())
                .paymentMode(order.getPreferredPaymentMode())
                .idempotencyKey(idempotencyKey)
                .amountMinor(order.getTotalMinor())
                .currency(order.getCurrency())
                .status(PaymentTransactionStatus.INITIATED)
                .build();
            newTx.setTenantId(tenantId);
            newTx = transactionRepository.save(newTx);

            PaymentOutboxEventType outboxType = (order.getPreferredPaymentMode() == PaymentMode.NATIVE_WHATSAPP)
                ? PaymentOutboxEventType.WHATSAPP_PAYMENT_MESSAGE_SEND
                : PaymentOutboxEventType.PAYMENT_PROVIDER_CREATE;

            PaymentOutboxEvent newOutbox = PaymentOutboxEvent.builder()
                .order(order)
                .transaction(newTx)
                .paymentIntegration(config)
                .eventType(outboxType)
                .payload(writeJson(Map.of("orderId", order.getId().toString(), "transactionId", newTx.getId().toString())))
                .status(PaymentOutboxStatus.PENDING)
                .build();
            newOutbox.setTenantId(tenantId);
            outboxRepository.save(newOutbox);

            log.info("Created new payment transaction attempt {} on order ref={} for resend", newTx.getId(), order.getReferenceId());
        }

        PaymentAuditLog auditLog = PaymentAuditLog.builder()
            .orderId(order.getId())
            .actorType("USER")
            .actorId(actorId)
            .action("RESEND")
            .newStatus("PAYMENT_REQUEST_SENT")
            .metadata(Map.of("orderRef", order.getReferenceId()))
            .build();
        auditLog.setTenantId(tenantId);
        auditLogRepository.save(auditLog);

        return mapToDto(order);
    }

    @Transactional
    public RefundResult refundOrder(UUID tenantId, UUID orderId, Long amountMinor, String reason, String actorId) {
        WhatsAppOrder order = orderRepository.findByIdAndTenantIdForUpdate(orderId, tenantId)
            .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        if (order.getPaymentStatus() != WhatsAppOrderPaymentStatus.PAID && order.getPaymentStatus() != WhatsAppOrderPaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException("Cannot refund an unpaid order (current status: " + order.getPaymentStatus() + ")");
        }

        List<PaymentTransaction> successfulTxList = transactionRepository.findAllByOrderIdAndTenantId(orderId, tenantId)
            .stream()
            .filter(t -> t.getStatus() == PaymentTransactionStatus.SUCCESS)
            .toList();

        if (successfulTxList.isEmpty()) {
            throw new IllegalStateException("No successful payment transaction found to refund");
        }

        PaymentTransaction tx = successfulTxList.get(0);
        tx = transactionRepository.findByIdAndTenantIdForUpdate(tx.getId(), tenantId).orElse(tx);

        // Calculate remaining refundable balance under row lock
        Long processedRefunds = refundRepository.calculateTotalProcessedRefundAmount(tx.getId());
        Long remainingRefundable = tx.getAmountMinor() - processedRefunds;

        if (amountMinor == null || amountMinor <= 0) {
            amountMinor = remainingRefundable;
        }

        if (amountMinor > remainingRefundable) {
            throw new IllegalArgumentException(String.format("Requested refund amount (₹%.2f) exceeds remaining refundable balance (₹%.2f)",
                amountMinor / 100.0, remainingRefundable / 100.0));
        }

        // Call Provider Refund API
        PaymentProvider provider = providerFactory.getProvider(tx.getProvider());
        RefundRequest request = RefundRequest.builder()
            .tenantId(tenantId)
            .orderId(order.getId())
            .transactionId(tx.getId())
            .providerPaymentId(tx.getProviderPaymentId())
            .amountMinor(amountMinor)
            .currency(tx.getCurrency())
            .reason(reason)
            .build();

        RefundResult result = provider.refund(tenantId, request);
        if (!result.isSuccess()) {
            log.error("Refund failed via provider {}: {}", tx.getProvider(), result.getErrorMessage());
            return result;
        }

        // Record PaymentRefund
        PaymentRefund refund = PaymentRefund.builder()
            .order(order)
            .transaction(tx)
            .paymentIntegration(tx.getPaymentIntegration())
            .providerRefundId(result.getProviderRefundId())
            .amountMinor(amountMinor)
            .currency(tx.getCurrency())
            .status(PaymentRefundStatus.PROCESSED)
            .reason(reason)
            .processedAt(Instant.now())
            .build();
        refund.setTenantId(tenantId);
        refundRepository.save(refund);

        // Update Order Payment Status
        Long newTotalRefunded = processedRefunds + amountMinor;
        if (newTotalRefunded.equals(tx.getAmountMinor())) {
            order.setPaymentStatus(WhatsAppOrderPaymentStatus.REFUNDED);
        } else {
            order.setPaymentStatus(WhatsAppOrderPaymentStatus.PARTIALLY_REFUNDED);
        }
        orderRepository.save(order);

        // Record Audit Log
        PaymentAuditLog auditLog = PaymentAuditLog.builder()
            .orderId(order.getId())
            .transactionId(tx.getId())
            .refundId(refund.getId())
            .actorType("USER")
            .actorId(actorId)
            .action("REFUND")
            .newStatus(order.getPaymentStatus().name())
            .metadata(Map.of("refundAmountMinor", amountMinor, "reason", reason != null ? reason : "N/A"))
            .build();
        auditLog.setTenantId(tenantId);
        auditLogRepository.save(auditLog);

        log.info("Processed refund of ₹{}/100 on order ref={} (New status: {})",
            amountMinor, order.getReferenceId(), order.getPaymentStatus());

        return result;
    }

    private WhatsAppOrderResponseDto mapToDto(WhatsAppOrder order) {
        List<WhatsAppOrderItem> items = itemRepository.findAllByOrderIdAndTenantId(order.getId(), order.getTenantId());
        List<PaymentTransaction> transactions = transactionRepository.findAllByOrderIdAndTenantId(order.getId(), order.getTenantId());
        List<PaymentRefund> refunds = refundRepository.findAllByOrderIdAndTenantId(order.getId(), order.getTenantId());
        List<PaymentAuditLog> auditLogs = auditLogRepository.findAllByOrderIdAndTenantId(order.getId(), order.getTenantId());

        return WhatsAppOrderResponseDto.builder()
            .id(order.getId())
            .referenceId(order.getReferenceId())
            .externalReferenceId(order.getExternalReferenceId())
            .customerId(order.getCustomerId())
            .customerWaId(order.getCustomerWaId())
            .customerName(order.getCustomerName())
            .currency(order.getCurrency())
            .subtotalMinor(order.getSubtotalMinor())
            .discountMinor(order.getDiscountMinor())
            .taxMinor(order.getTaxMinor())
            .shippingMinor(order.getShippingMinor())
            .totalMinor(order.getTotalMinor())
            .orderStatus(order.getOrderStatus())
            .paymentStatus(order.getPaymentStatus())
            .fulfillmentStatus(order.getFulfillmentStatus())
            .preferredPaymentMode(order.getPreferredPaymentMode())
            .preferredPaymentProvider(order.getPreferredPaymentProvider())
            .items(items.stream().map(i -> WhatsAppOrderItemDto.builder()
                .id(i.getId())
                .sku(i.getSku())
                .name(i.getName())
                .description(i.getDescription())
                .quantity(i.getQuantity())
                .unitPriceMinor(i.getUnitPriceMinor())
                .taxMinor(i.getTaxMinor())
                .discountMinor(i.getDiscountMinor())
                .lineTotalMinor(i.getLineTotalMinor())
                .metadata(i.getMetadata())
                .createdAt(i.getCreatedAt())
                .build()).toList())
            .transactions(transactions.stream().map(t -> PaymentTransactionDto.builder()
                .id(t.getId())
                .provider(t.getProvider())
                .paymentMode(t.getPaymentMode())
                .idempotencyKey(t.getIdempotencyKey())
                .providerOrderId(t.getProviderOrderId())
                .providerPaymentId(t.getProviderPaymentId())
                .providerTransactionId(t.getProviderTransactionId())
                .checkoutUrl(t.getCheckoutUrl())
                .amountMinor(t.getAmountMinor())
                .currency(t.getCurrency())
                .status(t.getStatus())
                .failureCode(t.getFailureCode())
                .failureReason(t.getFailureReason())
                .createdAt(t.getCreatedAt())
                .paidAt(t.getPaidAt())
                .build()).toList())
            .refunds(refunds.stream().map(r -> PaymentRefundDto.builder()
                .id(r.getId())
                .transactionId(r.getTransaction().getId())
                .providerRefundId(r.getProviderRefundId())
                .amountMinor(r.getAmountMinor())
                .currency(r.getCurrency())
                .status(r.getStatus())
                .reason(r.getReason())
                .createdAt(r.getCreatedAt())
                .processedAt(r.getProcessedAt())
                .build()).toList())
            .auditLogs(auditLogs.stream().map(l -> PaymentAuditLogDto.builder()
                .id(l.getId())
                .orderId(l.getOrderId())
                .transactionId(l.getTransactionId())
                .refundId(l.getRefundId())
                .actorType(l.getActorType())
                .actorId(l.getActorId())
                .action(l.getAction())
                .oldStatus(l.getOldStatus())
                .newStatus(l.getNewStatus())
                .metadata(l.getMetadata())
                .createdAt(l.getCreatedAt())
                .build()).toList())
            .createdAt(order.getCreatedAt())
            .updatedAt(order.getUpdatedAt())
            .paidAt(order.getPaidAt())
            .expiredAt(order.getExpiredAt())
            .cancelledAt(order.getCancelledAt())
            .build();
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
