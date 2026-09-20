package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.CommerceOrder;
import com.chatcrmlite.backend.models.CommerceOrderItem;
import com.chatcrmlite.backend.dto.payments.PaymentRequestDto;
import com.chatcrmlite.backend.dto.payments.PaymentItemDto;
import com.chatcrmlite.backend.dto.payments.WhatsAppOrderResponseDto;
import com.chatcrmlite.backend.repositories.CommerceOrderRepository;
import com.chatcrmlite.backend.services.payments.WhatsAppPaymentService;
import com.chatcrmlite.backend.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/whatsapp/orders")
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class CommerceOrderController {

    private final CommerceOrderRepository orderRepository;
    private final WhatsAppPaymentService paymentService;
    private final com.chatcrmlite.backend.services.payments.PaymentWebhookProcessor webhookProcessor;

    @GetMapping
    public ResponseEntity<Page<CommerceOrder>> getOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        
        UUID tenantId = TenantContext.getTenantId();
        Sort sort = sortDir.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Page<CommerceOrder> orders = orderRepository.findAllByTenantId(tenantId, PageRequest.of(page, size, sort));
        
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<CommerceOrder> getOrder(@PathVariable UUID orderId) {
        UUID tenantId = TenantContext.getTenantId();
        CommerceOrder order = orderRepository.findById(orderId)
                .filter(o -> o.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalStateException("Order not found or access denied"));
        return ResponseEntity.ok(order);
    }

    @PatchMapping("/{orderId}/status")
    public ResponseEntity<CommerceOrder> updateOrderStatus(
            @PathVariable UUID orderId,
            @RequestBody Map<String, String> payload) {
        
        UUID tenantId = TenantContext.getTenantId();
        CommerceOrder order = orderRepository.findById(orderId)
                .filter(o -> o.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalStateException("Order not found or access denied"));
        
        String newStatus = payload.get("status");
        if (newStatus != null && !newStatus.isBlank()) {
            order.setStatus(newStatus.toUpperCase());
            orderRepository.save(order);
        }
        
        return ResponseEntity.ok(order);
    }

    @PatchMapping("/{orderId}/payment")
    public ResponseEntity<CommerceOrder> updateOrderPaymentStatus(
            @PathVariable UUID orderId,
            @RequestBody Map<String, String> payload) {
        
        UUID tenantId = TenantContext.getTenantId();
        CommerceOrder order = orderRepository.findById(orderId)
                .filter(o -> o.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalStateException("Order not found or access denied"));
        
        String newPaymentStatus = payload.get("paymentStatus");
        if (newPaymentStatus != null && !newPaymentStatus.isBlank()) {
            order.setPaymentStatus(newPaymentStatus.toUpperCase());
            orderRepository.save(order);
        }
        
        return ResponseEntity.ok(order);
    }

    @PostMapping("/{orderId}/send-payment")
    public ResponseEntity<WhatsAppOrderResponseDto> sendPaymentLink(
            @PathVariable UUID orderId) {
        
        UUID tenantId = TenantContext.getTenantId();
        CommerceOrder order = orderRepository.findById(orderId)
                .filter(o -> o.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalStateException("Order not found or access denied"));
        
        PaymentRequestDto paymentRequest = new PaymentRequestDto();
        paymentRequest.setCustomerId(order.getCustomerId());
        paymentRequest.setCustomerWaId(order.getCustomerWaId());
        paymentRequest.setCustomerName(order.getCustomerName());
        paymentRequest.setExternalReferenceId(order.getId().toString());
        paymentRequest.setCurrency(order.getCurrency());
        paymentRequest.setDiscountMinor(order.getDiscount().multiply(new java.math.BigDecimal("100")).longValue());
        paymentRequest.setTaxMinor(order.getTax().multiply(new java.math.BigDecimal("100")).longValue());
        paymentRequest.setShippingMinor(order.getShipping().multiply(new java.math.BigDecimal("100")).longValue());
        
        java.util.List<PaymentItemDto> items = new java.util.ArrayList<>();
        for (CommerceOrderItem item : order.getItems()) {
            PaymentItemDto dto = new PaymentItemDto();
            dto.setSku(item.getProductRetailerId());
            dto.setName(item.getProductRetailerId());
            dto.setDescription("");
            dto.setQuantity(item.getQuantity());
            dto.setUnitPriceMinor(item.getItemPrice().multiply(new java.math.BigDecimal("100")).longValue());
            dto.setTaxMinor(0L);
            dto.setDiscountMinor(0L);
            items.add(dto);
        }
        paymentRequest.setItems(items);
        
        // This will create a WhatsAppOrder and send the payment link
        WhatsAppOrderResponseDto response = paymentService.createAndSendPayment(tenantId, paymentRequest, "USER");
        
        // Update CommerceOrder status to payment pending
        order.setPaymentStatus("PENDING");
        orderRepository.save(order);
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{orderId}/sync-payment")
    public ResponseEntity<CommerceOrder> syncPaymentStatus(@PathVariable UUID orderId) {
        UUID tenantId = TenantContext.getTenantId();
        CommerceOrder order = orderRepository.findById(orderId)
                .filter(o -> o.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalStateException("Order not found or access denied"));

        webhookProcessor.syncCommerceOrderFromProvider(order);
        CommerceOrder updated = orderRepository.findById(orderId).orElse(order);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{orderId}/mark-paid")
    public ResponseEntity<CommerceOrder> markOrderPaid(@PathVariable UUID orderId) {
        UUID tenantId = TenantContext.getTenantId();
        CommerceOrder order = orderRepository.findById(orderId)
                .filter(o -> o.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalStateException("Order not found or access denied"));

        webhookProcessor.markCommerceOrderPaid(order, "pay_manual_" + UUID.randomUUID().toString().substring(0, 8));
        CommerceOrder updated = orderRepository.findById(orderId).orElse(order);
        return ResponseEntity.ok(updated);
    }
}
