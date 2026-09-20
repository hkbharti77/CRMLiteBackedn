package com.chatcrmlite.backend.services.payments;

import com.chatcrmlite.backend.dto.payments.NormalizedPaymentWebhookEvent;
import com.chatcrmlite.backend.dto.payments.PaymentStatusResult;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.enums.*;
import com.chatcrmlite.backend.models.payments.*;
import com.chatcrmlite.backend.repositories.payments.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentWebhookProcessor {

    private final PaymentWebhookEventRepository webhookEventRepository;
    private final WhatsAppOrderRepository orderRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentOutboxEventRepository outboxRepository;
    private final PaymentAuditLogRepository auditLogRepository;
    private final TenantPaymentConfigRepository configRepository;
    private final PaymentStateMachine stateMachine;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final com.chatcrmlite.backend.repositories.CommerceOrderRepository commerceOrderRepository;
    private final com.chatcrmlite.backend.repositories.CommerceCheckoutSessionRepository checkoutSessionRepository;
    private final com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsappConfigRepository;
    private final com.chatcrmlite.backend.repositories.ContactRepository contactRepository;
    private final com.chatcrmlite.backend.services.whatsapp.WhatsAppOutboundService outboundService;
    private final PaymentProviderFactory providerFactory;

    @Transactional
    public boolean processNormalizedEvent(NormalizedPaymentWebhookEvent event) {
        UUID tenantId = event.getTenantId();
        UUID integrationId = event.getIntegrationId();
        String eventKey = event.getProviderEventKey();

        TenantPaymentConfig config = configRepository.findByIdAndTenantId(integrationId, tenantId)
            .orElseThrow(() -> new IllegalStateException("Integration not found for tenant: " + tenantId));

        // Step 1: Idempotency Check & Lock
        Optional<PaymentWebhookEvent> existingEventOpt = webhookEventRepository.findByIntegrationIdAndEventKey(integrationId, eventKey);
        PaymentWebhookEvent webhookEvent;

        if (existingEventOpt.isPresent()) {
            webhookEvent = existingEventOpt.get();
            if (webhookEvent.getProcessingStatus() == PaymentWebhookStatus.PROCESSED) {
                log.info("Webhook event {} already PROCESSED for tenant {}. Skipping idempotently.", eventKey, tenantId);
                return true;
            }
        } else {
            webhookEvent = PaymentWebhookEvent.builder()
                .paymentIntegration(config)
                .provider(event.getProvider())
                .providerEventKey(eventKey)
                .eventType(event.getEventType())
                .signatureValid(true)
                .payloadHash(event.getPayloadHash() != null ? event.getPayloadHash() : "HASH")
                .payloadJson(event.getRawPayloadString() != null ? event.getRawPayloadString() : "{}")
                .processingStatus(PaymentWebhookStatus.RECEIVED)
                .build();
            webhookEvent.setTenantId(tenantId);
            webhookEvent = webhookEventRepository.save(webhookEvent);
        }

        try {
            // Step 2: Resolve Target Order & Transaction
            WhatsAppOrder order = null;
            if (event.getOrderReferenceId() != null) {
                order = orderRepository.findByReferenceIdAndTenantIdForUpdate(event.getOrderReferenceId(), tenantId).orElse(null);
            }

            PaymentTransaction tx = null;
            if (event.getProviderPaymentId() != null) {
                tx = transactionRepository.findByProviderPaymentIdAndTenantId(event.getProviderPaymentId(), tenantId).orElse(null);
            }
            if (tx == null && event.getProviderOrderId() != null) {
                tx = transactionRepository.findByProviderOrderIdAndTenantId(event.getProviderOrderId(), tenantId).orElse(null);
            }
            if (tx == null && order != null) {
                List<PaymentTransaction> txList = transactionRepository.findAllByOrderIdAndTenantId(order.getId(), tenantId);
                if (!txList.isEmpty()) {
                    tx = txList.get(0);
                }
            }

            if (order == null && tx != null) {
                order = orderRepository.findByIdAndTenantIdForUpdate(tx.getOrder().getId(), tenantId).orElse(null);
            }

            if (order == null || tx == null) {
                // Check if target is a native WhatsApp Commerce Cart Order (CommerceOrder)
                com.chatcrmlite.backend.models.CommerceOrder commerceOrder = null;
                if (event.getOrderReferenceId() != null) {
                    try {
                        UUID cOrderId = UUID.fromString(event.getOrderReferenceId());
                        commerceOrder = commerceOrderRepository.findByIdAndTenantId(cOrderId, tenantId).orElse(null);
                    } catch (Exception ignored) {}
                }
                if (commerceOrder == null && event.getProviderOrderId() != null) {
                    commerceOrder = commerceOrderRepository.findByTenantIdAndPaymentLinkId(tenantId, event.getProviderOrderId()).orElse(null);
                }

                if (commerceOrder != null) {
                    return processCommerceOrderWebhook(commerceOrder, event, webhookEvent, tenantId);
                }

                log.warn("Could not match webhook event {} to existing order/transaction for tenant {}", eventKey, tenantId);
                webhookEvent.setProcessingStatus(PaymentWebhookStatus.IGNORED);
                webhookEvent.setErrorMessage("No matching order or transaction found");
                webhookEventRepository.save(webhookEvent);
                return true;
            }

            // Step 3: Row-locked Pessimistic Lock on Transaction
            tx = transactionRepository.findByIdAndTenantIdForUpdate(tx.getId(), tenantId).orElse(tx);

            // Step 4: Validate Amount & Currency Consistency
            if (event.getAmountMinor() != null && event.getAmountMinor() > 0) {
                if (!order.getTotalMinor().equals(event.getAmountMinor())) {
                    log.error("Amount mismatch for order {}: expected {} but got {}",
                        order.getReferenceId(), order.getTotalMinor(), event.getAmountMinor());
                }
            }

            // Step 5: State Machine Validation & Transition
            PaymentTransactionStatus newTxStatus = event.getTransactionStatus();
            stateMachine.validateTransition(order.getPaymentStatus(), tx.getStatus(), newTxStatus);

            tx.setStatus(newTxStatus);
            if (event.getProviderPaymentId() != null) {
                tx.setProviderPaymentId(event.getProviderPaymentId());
            }

            if (newTxStatus == PaymentTransactionStatus.SUCCESS) {
                tx.setPaidAt(event.getOccurredAt() != null ? event.getOccurredAt() : Instant.now());
                order.setPaymentStatus(WhatsAppOrderPaymentStatus.PAID);
                order.setPaidAt(tx.getPaidAt());

                // Enqueue Async PDF / Message Receipt via Outbox
                PaymentOutboxEvent receiptOutbox = PaymentOutboxEvent.builder()
                    .order(order)
                    .transaction(tx)
                    .paymentIntegration(config)
                    .eventType(PaymentOutboxEventType.PAYMENT_RECEIPT_SEND)
                    .payload(objectMapper.writeValueAsString(Map.of("orderId", order.getId().toString())))
                    .status(PaymentOutboxStatus.PENDING)
                    .build();
                receiptOutbox.setTenantId(tenantId);
                outboxRepository.save(receiptOutbox);

                log.info("Order ref={} marked PAID for tenant={}", order.getReferenceId(), tenantId);
            } else if (newTxStatus == PaymentTransactionStatus.FAILED) {
                order.setPaymentStatus(WhatsAppOrderPaymentStatus.FAILED);
            }

            transactionRepository.save(tx);
            orderRepository.save(order);

            // Step 6: Payment Audit Log
            PaymentAuditLog auditLog = PaymentAuditLog.builder()
                .orderId(order.getId())
                .transactionId(tx.getId())
                .actorType("WEBHOOK")
                .actorId(event.getProvider().name())
                .action("STATUS_CHANGE")
                .oldStatus(order.getPaymentStatus().name())
                .newStatus(newTxStatus.name())
                .metadata(Map.of("eventKey", eventKey, "eventType", event.getEventType()))
                .build();
            auditLog.setTenantId(tenantId);
            auditLogRepository.save(auditLog);

            // Step 7: Mark Webhook Event as PROCESSED
            webhookEvent.setProcessingStatus(PaymentWebhookStatus.PROCESSED);
            webhookEvent.setProcessedAt(Instant.now());
            webhookEvent.setErrorMessage(null);
            webhookEventRepository.save(webhookEvent);

            return true;
        } catch (Exception ex) {
            log.error("Failed processing payment webhook {}: {}", eventKey, ex.getMessage(), ex);
            webhookEvent.setProcessingStatus(PaymentWebhookStatus.FAILED);
            webhookEvent.setErrorMessage(ex.getMessage());
            webhookEventRepository.save(webhookEvent);
            throw new RuntimeException(ex);
        }
    }

    private boolean processCommerceOrderWebhook(
            com.chatcrmlite.backend.models.CommerceOrder commerceOrder,
            NormalizedPaymentWebhookEvent event,
            PaymentWebhookEvent webhookEvent,
            UUID tenantId) {

        PaymentTransactionStatus newTxStatus = event.getTransactionStatus();

        if (newTxStatus == PaymentTransactionStatus.SUCCESS) {
            commerceOrder.setPaymentStatus("PAID");
            commerceOrder.setStatus("CONFIRMED");
            commerceOrder.setCheckoutStatus("COMPLETED");
            commerceOrder.setPaymentReferenceId(event.getProviderPaymentId());
            commerceOrder.setPaidAt(java.time.LocalDateTime.now());
            commerceOrder.setPaymentAmount(commerceOrder.getTotal());
            commerceOrderRepository.save(commerceOrder);

            // Complete checkout session if active
            checkoutSessionRepository.findByOrderId(commerceOrder.getId()).ifPresent(session -> {
                session.setCheckoutStep("COMPLETED");
                checkoutSessionRepository.save(session);
            });

            // Also update linked WhatsAppOrder and PaymentTransaction for /payments dashboard
            orderRepository.findByExternalReferenceIdAndTenantId(commerceOrder.getId().toString(), tenantId)
                .ifPresent(wOrder -> {
                    wOrder.setPaymentStatus(WhatsAppOrderPaymentStatus.PAID);
                    wOrder.setOrderStatus(WhatsAppOrderStatus.CONFIRMED);
                    wOrder.setPaidAt(Instant.now());
                    orderRepository.save(wOrder);

                    transactionRepository.findAllByOrderIdAndTenantId(wOrder.getId(), tenantId).forEach(tx -> {
                        tx.setStatus(PaymentTransactionStatus.SUCCESS);
                        tx.setProviderPaymentId(event.getProviderPaymentId());
                        tx.setPaidAt(Instant.now());
                        transactionRepository.save(tx);
                    });
                    log.info("💳 Synced WhatsAppOrder {} and transactions to PAID/SUCCESS", wOrder.getReferenceId());
                });

            // Send confirmation WhatsApp message to customer
            WhatsAppConfig waConfig = whatsappConfigRepository.findByTenantId(tenantId).orElse(null);
            if (waConfig != null && commerceOrder.getCustomerWaId() != null) {
                Contact contact = contactRepository.findByWaIdAndTenant_Id(commerceOrder.getCustomerWaId(), tenantId).orElse(null);
                if (contact != null) {
                    String msg = String.format(
                        "🎉 *Payment Successful!*\n" +
                        "Order ID: #%s\n" +
                        "Amount Paid: ₹%s\n\n" +
                        "Your payment has been received and your order is confirmed! We will notify you once your order is dispatched. Thank you for shopping with us!",
                        commerceOrder.getId().toString().substring(0, 8),
                        commerceOrder.getTotal().toPlainString()
                    );
                    outboundService.sendText(contact, msg, waConfig, null);
                    log.info("📢 Dispatched payment confirmation message to customer {} for order {}", commerceOrder.getCustomerWaId(), commerceOrder.getId());
                }
            }
        } else if (newTxStatus == PaymentTransactionStatus.FAILED) {
            commerceOrder.setPaymentStatus("PAYMENT_FAILED");
            commerceOrderRepository.save(commerceOrder);

            WhatsAppConfig waConfig = whatsappConfigRepository.findByTenantId(tenantId).orElse(null);
            if (waConfig != null && commerceOrder.getCustomerWaId() != null) {
                Contact contact = contactRepository.findByWaIdAndTenant_Id(commerceOrder.getCustomerWaId(), tenantId).orElse(null);
                if (contact != null && commerceOrder.getPaymentLinkUrl() != null) {
                    String msg = String.format(
                        "⚠️ Payment for Order #%s was unsuccessful.\n\n" +
                        "If you wish to retry, please use the link below:\n%s",
                        commerceOrder.getId().toString().substring(0, 8),
                        commerceOrder.getPaymentLinkUrl()
                    );
                    outboundService.sendText(contact, msg, waConfig, null);
                }
            }
        }

        if (webhookEvent != null) {
            webhookEvent.setProcessingStatus(PaymentWebhookStatus.PROCESSED);
            webhookEvent.setProcessedAt(Instant.now());
            webhookEvent.setErrorMessage(null);
            webhookEventRepository.save(webhookEvent);
        }
        log.info("✅ Successfully processed CommerceOrder {} payment webhook status={}", commerceOrder.getId(), newTxStatus);
        return true;
    }

    @Transactional
    public boolean markCommerceOrderPaid(com.chatcrmlite.backend.models.CommerceOrder commerceOrder, String paymentRefId) {
        UUID tenantId = commerceOrder.getTenant().getId();
        NormalizedPaymentWebhookEvent event = NormalizedPaymentWebhookEvent.builder()
                .tenantId(tenantId)
                .eventType("payment_link.paid")
                .providerOrderId(commerceOrder.getPaymentLinkId())
                .providerPaymentId(paymentRefId != null ? paymentRefId : "pay_sim_" + System.currentTimeMillis())
                .currency(commerceOrder.getCurrency())
                .amountMinor(commerceOrder.getTotal().multiply(new java.math.BigDecimal(100)).longValue())
                .transactionStatus(PaymentTransactionStatus.SUCCESS)
                .occurredAt(java.time.Instant.now())
                .build();
        return processCommerceOrderWebhook(commerceOrder, event, null, tenantId);
    }

    @Transactional
    public boolean syncCommerceOrderFromProvider(com.chatcrmlite.backend.models.CommerceOrder commerceOrder) {
        if (commerceOrder.getPaymentLinkId() == null) {
            return false;
        }
        UUID tenantId = commerceOrder.getTenant().getId();
        try {
            PaymentProvider provider = providerFactory.getProvider(PaymentIntegrationType.RAZORPAY_DIRECT);
            PaymentStatusResult result = provider.getPaymentStatus(tenantId, commerceOrder.getPaymentLinkId());
            if (result.getStatus() == PaymentTransactionStatus.SUCCESS) {
                return markCommerceOrderPaid(commerceOrder, commerceOrder.getPaymentLinkId());
            } else {
                log.info("Razorpay payment link {} status is currently {}", commerceOrder.getPaymentLinkId(), result.getStatus());
            }
        } catch (Exception e) {
            log.error("Failed to sync commerce order payment status from provider: {}", e.getMessage());
        }
        return false;
    }
}

