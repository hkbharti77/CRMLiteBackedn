package com.chatcrmlite.backend.services.payments;

import com.chatcrmlite.backend.dto.payments.PaymentItemDto;
import com.chatcrmlite.backend.dto.payments.PaymentRequestDto;
import com.chatcrmlite.backend.dto.payments.WhatsAppOrderResponseDto;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.enums.*;
import com.chatcrmlite.backend.models.payments.*;
import com.chatcrmlite.backend.repositories.payments.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentOrchestrator {

    private final WhatsAppOrderRepository orderRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentOutboxEventRepository outboxRepository;
    private final TenantPaymentConfigRepository configRepository;
    private final PaymentAuditLogRepository auditLogRepository;
    private final com.chatcrmlite.backend.services.whatsapp.WhatsAppSessionWindowService sessionWindowService;
    private final PaymentTemplateDefinitionRegistry templateRegistry;
    private final ObjectMapper objectMapper;

    @Transactional
    public WhatsAppOrder createPaymentOrder(UUID tenantId, PaymentRequestDto dto, String actorId, String actorType) {
        // 1. Calculate & validate financial minor units strictly on backend
        long calculatedSubtotal = 0L;
        List<WhatsAppOrderItem> items = new ArrayList<>();
        
        for (PaymentItemDto itemDto : dto.getItems()) {
            long lineTotal = (itemDto.getUnitPriceMinor() * itemDto.getQuantity()) + itemDto.getTaxMinor() - itemDto.getDiscountMinor();
            calculatedSubtotal += (itemDto.getUnitPriceMinor() * itemDto.getQuantity());

            WhatsAppOrderItem item = WhatsAppOrderItem.builder()
                .sku(itemDto.getSku())
                .name(itemDto.getName())
                .description(itemDto.getDescription())
                .quantity(itemDto.getQuantity())
                .unitPriceMinor(itemDto.getUnitPriceMinor())
                .taxMinor(itemDto.getTaxMinor() != null ? itemDto.getTaxMinor() : 0L)
                .discountMinor(itemDto.getDiscountMinor() != null ? itemDto.getDiscountMinor() : 0L)
                .lineTotalMinor(lineTotal)
                .metadata(itemDto.getMetadata())
                .build();
            item.setTenantId(tenantId);
            items.add(item);
        }

        long discount = dto.getDiscountMinor() != null ? dto.getDiscountMinor() : 0L;
        long tax = dto.getTaxMinor() != null ? dto.getTaxMinor() : 0L;
        long shipping = dto.getShippingMinor() != null ? dto.getShippingMinor() : 0L;
        long calculatedTotal = calculatedSubtotal - discount + tax + shipping;

        if (calculatedTotal <= 0) {
            throw new IllegalArgumentException("Total order amount must be greater than zero");
        }

        // 2. Evaluate 24-Hour WhatsApp Session Window Policy
        var sessionInfo = sessionWindowService.getSessionStatus(tenantId, dto.getCustomerWaId());
        PaymentDispatchMode effectiveDispatchMode = dto.getDispatchMode();

        if (sessionInfo.getSessionStatus() == WhatsAppSessionStatus.CLOSED || sessionInfo.getSessionStatus() == WhatsAppSessionStatus.UNKNOWN) {
            if (dto.getPreferredPaymentMode() == PaymentMode.NATIVE_WHATSAPP && effectiveDispatchMode != PaymentDispatchMode.PAYMENT_TEMPLATE) {
                throw new IllegalStateException("Customer 24-hour service window has expired. WhatsApp native interactive checkout is not permitted; an approved WhatsApp Payment Template is required.");
            }
            effectiveDispatchMode = PaymentDispatchMode.PAYMENT_TEMPLATE;
        } else {
            if (effectiveDispatchMode == null) {
                effectiveDispatchMode = PaymentDispatchMode.SESSION_MESSAGE;
            }
        }

        String templateKey = dto.getTemplateDefinitionKey();
        if (effectiveDispatchMode == PaymentDispatchMode.PAYMENT_TEMPLATE) {
            if (templateKey == null || templateKey.trim().isEmpty()) {
                templateKey = PaymentTemplateDefinitionRegistry.KEY_ORDER_INVOICE;
            }
            if (templateRegistry.getDefinition(templateKey).isEmpty()) {
                throw new IllegalArgumentException("Unknown payment template definition key: " + templateKey);
            }
        }

        // 3. Select / route payment provider (Pre-dispatch evaluation)
        TenantPaymentConfig selectedConfig = determineProvider(tenantId, dto.getPreferredPaymentProvider(), dto.getPreferredPaymentMode());
        PaymentIntegrationType providerType = selectedConfig.getIntegrationType();
        PaymentMode paymentMode = (providerType == PaymentIntegrationType.META_WHATSAPP && effectiveDispatchMode != PaymentDispatchMode.PAYMENT_TEMPLATE)
            ? PaymentMode.NATIVE_WHATSAPP
            : PaymentMode.DIRECT_LINK;

        // 4. Create WhatsAppOrder
        String referenceId = "ORD-" + Instant.now().getEpochSecond() + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        WhatsAppOrder order = WhatsAppOrder.builder()
            .referenceId(referenceId)
            .externalReferenceId(dto.getExternalReferenceId())
            .customerId(dto.getCustomerId())
            .customerWaId(dto.getCustomerWaId())
            .customerName(dto.getCustomerName())
            .currency(dto.getCurrency() != null ? dto.getCurrency() : "INR")
            .subtotalMinor(calculatedSubtotal)
            .discountMinor(discount)
            .taxMinor(tax)
            .shippingMinor(shipping)
            .totalMinor(calculatedTotal)
            .orderStatus(WhatsAppOrderStatus.CREATED)
            .paymentStatus(WhatsAppOrderPaymentStatus.CREATED)
            .fulfillmentStatus(WhatsAppOrderFulfillmentStatus.UNFULFILLED)
            .preferredPaymentMode(paymentMode)
            .preferredPaymentProvider(providerType)
            .build();
        order.setTenant(tenant);

        for (WhatsAppOrderItem it : items) {
            order.addItem(it);
        }
        order = orderRepository.save(order);

        // 5. Create PaymentTransaction attempt
        String idempotencyKey = "TX_" + order.getId() + "_" + UUID.randomUUID().toString().substring(0, 8);
        PaymentTransaction tx = PaymentTransaction.builder()
            .order(order)
            .paymentIntegration(selectedConfig)
            .provider(providerType)
            .paymentMode(paymentMode)
            .idempotencyKey(idempotencyKey)
            .amountMinor(calculatedTotal)
            .currency(order.getCurrency())
            .status(PaymentTransactionStatus.INITIATED)
            .build();
        tx.setTenant(tenant);
        tx = transactionRepository.save(tx);

        // 6. Create Initial Outbox Event
        PaymentOutboxEventType outboxEventType;
        if (effectiveDispatchMode == PaymentDispatchMode.PAYMENT_TEMPLATE) {
            outboxEventType = PaymentOutboxEventType.PAYMENT_PROVIDER_CREATE;
        } else if (paymentMode == PaymentMode.NATIVE_WHATSAPP) {
            outboxEventType = PaymentOutboxEventType.WHATSAPP_PAYMENT_MESSAGE_SEND;
        } else {
            outboxEventType = PaymentOutboxEventType.PAYMENT_PROVIDER_CREATE;
        }

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("orderId", order.getId().toString());
        outboxPayload.put("transactionId", tx.getId().toString());
        outboxPayload.put("integrationId", selectedConfig.getId().toString());
        outboxPayload.put("dispatchMode", effectiveDispatchMode.name());
        if (templateKey != null) {
            outboxPayload.put("templateDefinitionKey", templateKey);
        }
        if (dto.getTemplateParameters() != null) {
            outboxPayload.put("templateParameters", dto.getTemplateParameters());
        }

        PaymentOutboxEvent outboxEvent = PaymentOutboxEvent.builder()
            .order(order)
            .transaction(tx)
            .paymentIntegration(selectedConfig)
            .eventType(outboxEventType)
            .payload(writeJson(outboxPayload))
            .status(PaymentOutboxStatus.PENDING)
            .build();
        outboxEvent.setTenant(tenant);
        outboxRepository.save(outboxEvent);

        // 6. Record Audit Log
        PaymentAuditLog auditLog = PaymentAuditLog.builder()
            .orderId(order.getId())
            .transactionId(tx.getId())
            .actorType(actorType != null ? actorType : "USER")
            .actorId(actorId)
            .action("CREATE_ORDER")
            .newStatus("CREATED")
            .metadata(Map.of("referenceId", referenceId, "totalMinor", calculatedTotal, "provider", providerType.name()))
            .build();
        auditLog.setTenant(tenant);
        auditLogRepository.save(auditLog);

        log.info("Created WhatsApp payment order ref={} totalMinor={} provider={} for tenant={}",
            referenceId, calculatedTotal, providerType, tenantId);

        return order;
    }

    private TenantPaymentConfig determineProvider(UUID tenantId, PaymentIntegrationType preferredType, PaymentMode preferredMode) {
        if (preferredType != null) {
            Optional<TenantPaymentConfig> config = configRepository.findActiveByTenantIdAndType(tenantId, preferredType);
            if (config.isPresent()) return config.get();
        }

        if (preferredMode == PaymentMode.NATIVE_WHATSAPP) {
            Optional<TenantPaymentConfig> metaConfig = configRepository.findActiveByTenantIdAndType(tenantId, PaymentIntegrationType.META_WHATSAPP);
            if (metaConfig.isPresent()) return metaConfig.get();
        }

        // Fallback hierarchy: Razorpay Direct -> PayU Direct -> Stripe Direct -> Meta Native
        List<TenantPaymentConfig> activeConfigs = configRepository.findAllByTenantIdAndStatus(tenantId, PaymentIntegrationStatus.ACTIVE);
        if (activeConfigs.isEmpty()) {
            throw new IllegalStateException("No active payment integration configured for tenant: " + tenantId);
        }

        for (PaymentIntegrationType type : List.of(PaymentIntegrationType.RAZORPAY_DIRECT, PaymentIntegrationType.META_WHATSAPP, PaymentIntegrationType.PAYU_DIRECT, PaymentIntegrationType.STRIPE_DIRECT)) {
            for (TenantPaymentConfig c : activeConfigs) {
                if (c.getIntegrationType() == type) {
                    return c;
                }
            }
        }

        return activeConfigs.get(0);
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
