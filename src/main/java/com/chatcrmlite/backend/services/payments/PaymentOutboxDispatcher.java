package com.chatcrmlite.backend.services.payments;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.payments.PaymentCreationContext;
import com.chatcrmlite.backend.dto.payments.ProviderPaymentCreationResult;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.enums.*;
import com.chatcrmlite.backend.models.payments.*;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.payments.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class PaymentOutboxDispatcher {

    private final PaymentOutboxEventRepository outboxRepository;
    private final WhatsAppOrderRepository orderRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final TenantPaymentConfigRepository configRepository;
    private final WhatsAppConfigRepository whatsAppConfigRepository;
    private final PaymentAuditLogRepository auditLogRepository;
    private final PaymentProviderFactory providerFactory;
    private final WhatsAppPaymentMessageFactory messageFactory;
    private final MetaWhatsAppClient metaWhatsAppClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private final String instanceId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public PaymentOutboxDispatcher(
        PaymentOutboxEventRepository outboxRepository,
        WhatsAppOrderRepository orderRepository,
        PaymentTransactionRepository transactionRepository,
        TenantPaymentConfigRepository configRepository,
        WhatsAppConfigRepository whatsAppConfigRepository,
        PaymentAuditLogRepository auditLogRepository,
        PaymentProviderFactory providerFactory,
        WhatsAppPaymentMessageFactory messageFactory,
        MetaWhatsAppClient metaWhatsAppClient,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.outboxRepository = outboxRepository;
        this.orderRepository = orderRepository;
        this.transactionRepository = transactionRepository;
        this.configRepository = configRepository;
        this.whatsAppConfigRepository = whatsAppConfigRepository;
        this.auditLogRepository = auditLogRepository;
        this.providerFactory = providerFactory;
        this.messageFactory = messageFactory;
        this.metaWhatsAppClient = metaWhatsAppClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-interval-ms:3000}")
    public void processOutboxEvents() {
        // Step 1: Claim Phase (Short DB Transaction #1)
        List<PaymentOutboxEvent> claimedEvents = claimPendingEvents();
        if (claimedEvents == null || claimedEvents.isEmpty()) {
            return;
        }

        for (PaymentOutboxEvent event : claimedEvents) {
            try {
                // Step 2: External I/O (Outside any active DB transaction)
                OutboxExecutionResult result = executeEventTask(event);

                // Step 3: Result Save Phase (Short DB Transaction #2)
                saveExecutionResult(event.getId(), result);
            } catch (Exception ex) {
                log.error("Outbox task failed for event {}: {}", event.getId(), ex.getMessage(), ex);
                handleEventFailure(event.getId(), ex.getMessage());
            }
        }
    }

    @Scheduled(fixedDelayString = "${payment.outbox.stale-check-interval-ms:60000}")
    public void recoverStaleLocks() {
        transactionTemplate.executeWithoutResult(status -> {
            Instant staleCutoff = Instant.now().minus(5, ChronoUnit.MINUTES);
            int recovered = outboxRepository.recoverStaleLocks(staleCutoff);
            if (recovered > 0) {
                log.warn("Recovered {} stale outbox event locks stuck in PROCESSING", recovered);
            }
        });
    }

    public List<PaymentOutboxEvent> claimPendingEvents() {
        return transactionTemplate.execute(status -> {
            List<PaymentOutboxEvent> pending = outboxRepository.pollPendingEventsWithLock(Instant.now(), 20);
            if (pending == null || pending.isEmpty()) {
                return List.of();
            }

            // Hydrate lazy relationships while active DB session is open
            for (PaymentOutboxEvent event : pending) {
                if (event.getOrder() != null) {
                    event.getOrder().getReferenceId();
                    if (event.getOrder().getItems() != null) {
                        event.getOrder().getItems().size();
                    }
                }
                if (event.getTransaction() != null) {
                    event.getTransaction().getIdempotencyKey();
                }
                if (event.getPaymentIntegration() != null) {
                    event.getPaymentIntegration().getIntegrationType();
                }
            }

            List<UUID> ids = pending.stream().map(PaymentOutboxEvent::getId).toList();
            outboxRepository.markEventsAsProcessing(ids, Instant.now(), instanceId);
            return pending;
        });
    }

    private OutboxExecutionResult executeEventTask(PaymentOutboxEvent event) throws Exception {
        WhatsAppOrder order = event.getOrder();
        PaymentTransaction tx = event.getTransaction();
        TenantPaymentConfig config = event.getPaymentIntegration();
        UUID tenantId = event.getTenantId();

        JsonNode payload = objectMapper.readTree(event.getPayload());

        switch (event.getEventType()) {
            case PAYMENT_PROVIDER_CREATE -> {
                PaymentProvider provider = providerFactory.getProvider(tx.getProvider());
                PaymentCreationContext context = PaymentCreationContext.builder()
                    .tenantId(tenantId)
                    .orderId(order.getId())
                    .transactionId(tx.getId())
                    .idempotencyKey(tx.getIdempotencyKey())
                    .orderReference(order.getReferenceId())
                    .customerWaId(order.getCustomerWaId())
                    .customerName(order.getCustomerName())
                    .amountMinor(tx.getAmountMinor())
                    .currency(tx.getCurrency())
                    .build();

                ProviderPaymentCreationResult creationResult = provider.createProviderOrder(tenantId, context);
                if (!creationResult.isSuccess()) {
                    throw new RuntimeException("Provider payment creation failed: " + creationResult.getErrorMessage());
                }

                PaymentOutboxEventType nextType = PaymentOutboxEventType.WHATSAPP_PAYMENT_MESSAGE_SEND;
                if (payload.has("dispatchMode") && "PAYMENT_TEMPLATE".equalsIgnoreCase(payload.get("dispatchMode").asText())) {
                    nextType = PaymentOutboxEventType.WHATSAPP_PAYMENT_TEMPLATE_SEND;
                }

                return OutboxExecutionResult.builder()
                    .success(true)
                    .providerOrderId(creationResult.getProviderOrderId())
                    .checkoutUrl(creationResult.getCheckoutUrl())
                    .enqueueNextEvent(nextType)
                    .build();
            }

            case WHATSAPP_PAYMENT_TEMPLATE_SEND -> {
                WhatsAppConfig waConfig = whatsAppConfigRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> new IllegalStateException("WhatsApp account not configured for tenant: " + tenantId));

                String templateKey = payload.path("templateDefinitionKey").asText("payment_order_invoice_v1");
                java.util.Map<String, String> customParams = new java.util.HashMap<>();
                if (payload.has("templateParameters") && payload.get("templateParameters").isObject()) {
                    payload.get("templateParameters").fields().forEachRemaining(entry -> {
                        customParams.put(entry.getKey(), entry.getValue().asText());
                    });
                }

                Object messagePayload = messageFactory.buildPaymentTemplateMessage(order, tx, templateKey, customParams);

                String response = metaWhatsAppClient.sendInteractiveObject(
                    waConfig.getPhoneNumberId(),
                    waConfig.getAccessToken(),
                    messagePayload
                );

                log.info("Dispatched WhatsApp payment template '{}' for order ref={} to {}: response={}",
                    templateKey, order.getReferenceId(), order.getCustomerWaId(), response);

                return OutboxExecutionResult.builder()
                    .success(true)
                    .markOrderPaymentRequestSent(true)
                    .build();
            }

            case WHATSAPP_PAYMENT_MESSAGE_SEND -> {
                WhatsAppConfig waConfig = whatsAppConfigRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> new IllegalStateException("WhatsApp account not configured for tenant: " + tenantId));

                Object messagePayload;
                if (tx.getPaymentMode() == PaymentMode.NATIVE_WHATSAPP) {
                    messagePayload = messageFactory.buildMetaNativeOrderDetails(order, tx, config);
                } else {
                    messagePayload = messageFactory.buildDirectGatewayCtaMessage(order, tx);
                }

                String response = metaWhatsAppClient.sendInteractiveObject(
                    waConfig.getPhoneNumberId(),
                    waConfig.getAccessToken(),
                    messagePayload
                );

                log.info("Dispatched WhatsApp payment message for order ref={} to {}: response={}",
                    order.getReferenceId(), order.getCustomerWaId(), response);

                return OutboxExecutionResult.builder()
                    .success(true)
                    .markOrderPaymentRequestSent(true)
                    .build();
            }

            case PAYMENT_RECEIPT_SEND -> {
                WhatsAppConfig waConfig = whatsAppConfigRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> new IllegalStateException("WhatsApp account not configured for tenant: " + tenantId));

                String receiptText = messageFactory.buildReceiptTextMessage(order, tx);
                metaWhatsAppClient.sendMessage(
                    order.getCustomerWaId(),
                    receiptText,
                    waConfig.getAccessToken(),
                    waConfig.getPhoneNumberId()
                );

                log.info("Dispatched WhatsApp payment receipt for order ref={} to {}", order.getReferenceId(), order.getCustomerWaId());
                return OutboxExecutionResult.builder().success(true).build();
            }

            case WHATSAPP_ORDER_STATUS_SEND -> {
                WhatsAppConfig waConfig = whatsAppConfigRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> new IllegalStateException("WhatsApp account not configured for tenant: " + tenantId));

                String statusDesc = payload.path("statusDescription").asText("Order update");
                Object messagePayload = messageFactory.buildOrderStatusUpdate(order, statusDesc);
                metaWhatsAppClient.sendInteractiveObject(waConfig.getPhoneNumberId(), waConfig.getAccessToken(), messagePayload);

                return OutboxExecutionResult.builder().success(true).build();
            }

            default -> {
                return OutboxExecutionResult.builder().success(true).build();
            }
        }
    }

    public void saveExecutionResult(UUID eventId, OutboxExecutionResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            PaymentOutboxEvent event = outboxRepository.findById(eventId).orElse(null);
            if (event == null) return;

            event.setStatus(PaymentOutboxStatus.SENT);
            event.setSentAt(Instant.now());
            event.setErrorMessage(null);

            PaymentTransaction tx = event.getTransaction();
            WhatsAppOrder order = event.getOrder();

            if (result.getProviderOrderId() != null && tx != null) {
                tx.setProviderOrderId(result.getProviderOrderId());
            }
            if (result.getCheckoutUrl() != null && tx != null) {
                tx.setCheckoutUrl(result.getCheckoutUrl());
                tx.setStatus(PaymentTransactionStatus.PENDING);
                transactionRepository.save(tx);
            }

            if (result.isMarkOrderPaymentRequestSent() && order != null) {
                if (order.getPaymentStatus() == WhatsAppOrderPaymentStatus.CREATED) {
                    order.setPaymentStatus(WhatsAppOrderPaymentStatus.PAYMENT_REQUEST_SENT);
                    orderRepository.save(order);
                }
            }

            // If next step required (e.g. provider created -> send WhatsApp message)
            if (result.getEnqueueNextEvent() != null) {
                PaymentOutboxEvent nextEvent = PaymentOutboxEvent.builder()
                    .order(order)
                    .transaction(tx)
                    .paymentIntegration(event.getPaymentIntegration())
                    .eventType(result.getEnqueueNextEvent())
                    .payload(event.getPayload())
                    .status(PaymentOutboxStatus.PENDING)
                    .build();
                nextEvent.setTenant(event.getTenant());
                outboxRepository.save(nextEvent);
            }

            outboxRepository.save(event);
        });
    }

    public void handleEventFailure(UUID eventId, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            PaymentOutboxEvent event = outboxRepository.findById(eventId).orElse(null);
            if (event == null) return;

            int newRetry = event.getRetryCount() + 1;
            event.setRetryCount(newRetry);
            event.setErrorMessage(errorMessage);

            if (newRetry >= event.getMaxRetries()) {
                event.setStatus(PaymentOutboxStatus.DEAD);
                log.error("Outbox event {} reached MAX retries and is marked DEAD: {}", eventId, errorMessage);

                // Update associated transaction and order to FAILED
                PaymentTransaction tx = event.getTransaction();
                if (tx != null) {
                    tx.setStatus(PaymentTransactionStatus.FAILED);
                    tx.setFailureReason("Outbox dispatch failure: " + errorMessage);
                    transactionRepository.save(tx);
                }
                WhatsAppOrder order = event.getOrder();
                if (order != null && order.getPaymentStatus() == WhatsAppOrderPaymentStatus.CREATED) {
                    order.setPaymentStatus(WhatsAppOrderPaymentStatus.FAILED);
                    orderRepository.save(order);
                }
            } else {
                // Exponential backoff: 5s, 15s, 45s, 135s...
                long backoffSeconds = (long) Math.pow(3, newRetry) * 5;
                event.setNextRetryAt(Instant.now().plus(backoffSeconds, ChronoUnit.SECONDS));
                event.setStatus(PaymentOutboxStatus.PENDING);
                event.setLockedAt(null);
                event.setLockedBy(null);
            }

            outboxRepository.save(event);
        });
    }

    @lombok.Builder
    @lombok.Getter
    private static class OutboxExecutionResult {
        private boolean success;
        private String providerOrderId;
        private String checkoutUrl;
        private boolean markOrderPaymentRequestSent;
        private PaymentOutboxEventType enqueueNextEvent;
    }
}
