package com.chatcrmlite.backend.services.whatsapp.catalog;

import com.chatcrmlite.backend.dto.whatsapp.inbound.OrderMessageDto;
import com.chatcrmlite.backend.dto.whatsapp.inbound.OrderProductItemDto;
import com.chatcrmlite.backend.models.CommerceOrder;
import com.chatcrmlite.backend.models.CommerceOrderItem;
import com.chatcrmlite.backend.models.CommerceCatalog;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.repositories.CommerceCatalogRepository;
import com.chatcrmlite.backend.repositories.CommerceOrderRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.services.whatsapp.checkout.CommerceCheckoutService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppOrderService {

    private final CommerceOrderRepository orderRepository;
    private final TenantRepository tenantRepository;
    private final CommerceCatalogRepository catalogRepository;
    private final CommerceCheckoutService checkoutService;

    @Transactional
    public CommerceOrder processOrder(UUID tenantId, String wabaId, String phoneNumberId, String waMessageId, Contact contact, JsonNode orderNode) {
        if (orderRepository.findByTenantIdAndWhatsappMessageId(tenantId, waMessageId).isPresent()) {
            log.info("Order for message {} already exists. Skipping.", waMessageId);
            return null;
        }

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();

        String catalogId = orderNode.path("catalog_id").asText();
        String text = orderNode.path("text").asText("");
        JsonNode productItemsNode = orderNode.path("product_items");

        // Snapshot current catalog payment configuration
        Optional<CommerceCatalog> catalogOpt = catalogRepository.findByTenantIdAndMetaCatalogId(tenantId, catalogId);
        boolean onlinePaymentEnabled = catalogOpt.map(CommerceCatalog::isOnlinePaymentEnabled).orElse(true);
        boolean codEnabled = catalogOpt.map(CommerceCatalog::isCodEnabled).orElse(false);

        CommerceOrder order = CommerceOrder.builder()
                .tenant(tenant)
                .wabaId(wabaId)
                .phoneNumberId(phoneNumberId)
                .catalogId(catalogId)
                .customerId(contact.getId())
                .customerWaId(contact.getWaId())
                .customerName(contact.getName())
                .whatsappMessageId(waMessageId)
                .customerNote(text)
                .currency("INR") // Default INR for India commerce
                .checkoutStatus("AWAITING_CUSTOMER_DETAILS")
                .paymentStatus("UNPAID")
                .onlinePaymentEnabledAtOrder(onlinePaymentEnabled)
                .codEnabledAtOrder(codEnabled)
                .build();

        BigDecimal total = BigDecimal.ZERO;

        if (productItemsNode != null && productItemsNode.isArray()) {
            for (JsonNode itemNode : productItemsNode) {
                String productRetailerId = itemNode.path("product_retailer_id").asText();
                int quantity = itemNode.path("quantity").asInt(1);
                String currency = itemNode.path("currency").asText("INR");
                BigDecimal itemPrice = new BigDecimal(itemNode.path("item_price").asText("0.0"));

                CommerceOrderItem item = CommerceOrderItem.builder()
                        .order(order)
                        .productRetailerId(productRetailerId)
                        .quantity(quantity)
                        .itemPrice(itemPrice)
                        .currency(currency)
                        .build();

                order.getItems().add(item);
                
                // Keep the currency of the first item
                if (order.getItems().size() == 1) {
                    order.setCurrency(currency);
                }

                total = total.add(itemPrice.multiply(BigDecimal.valueOf(quantity)));
            }
        }

        order.setSubtotal(total);
        order.setTotal(total);

        order = orderRepository.saveAndFlush(order);
        log.info("🛒 Created CommerceOrder {} for tenant {} (online={}, cod={})", order.getId(), tenantId, onlinePaymentEnabled, codEnabled);

        // Initiate durable checkout session & address prompt dispatch
        checkoutService.initiateCheckout(order, contact);
        
        return order;
    }
}

