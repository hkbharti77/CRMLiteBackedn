package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.payments.PaymentRequestDto;
import com.chatcrmlite.backend.dto.payments.RefundResult;
import com.chatcrmlite.backend.dto.payments.WhatsAppOrderResponseDto;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.enums.WhatsAppOrderPaymentStatus;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.payments.WhatsAppPaymentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/whatsapp/payments")
@Tag(name = "WhatsApp In-Chat Payments", description = "Endpoints for in-chat payment links, order details, and billing operations")
@Slf4j
@RequiredArgsConstructor
public class WhatsAppPaymentOrderController {

    private final WhatsAppPaymentService paymentService;
    private final com.chatcrmlite.backend.services.whatsapp.WhatsAppSessionWindowService sessionWindowService;
    private final com.chatcrmlite.backend.services.payments.PaymentTemplateDefinitionRegistry templateRegistry;
    private final UserRepository userRepository;

    @GetMapping("/session-status")
    public ResponseEntity<com.chatcrmlite.backend.dto.payments.WhatsAppSessionInfoDto> getSessionStatus(
        @AuthenticationPrincipal String email,
        @RequestParam("customerWaId") String customerWaId
    ) {
        UUID tenantId = getTenantId(email);
        return ResponseEntity.ok(sessionWindowService.getSessionStatus(tenantId, customerWaId));
    }

    @GetMapping("/templates/definitions")
    public ResponseEntity<List<com.chatcrmlite.backend.dto.payments.AvailableTemplateDto>> getTemplateDefinitions(
        @AuthenticationPrincipal String email
    ) {
        // Enforces authentication and tenant isolation
        getTenantId(email);
        return ResponseEntity.ok(templateRegistry.getAllDefinitions());
    }

    @PostMapping("/send-bill")
    public ResponseEntity<WhatsAppOrderResponseDto> sendBill(
        @AuthenticationPrincipal String email,
        @Valid @RequestBody PaymentRequestDto dto
    ) {
        User user = getUser(email);
        UUID tenantId = user.getTenant().getId();
        String actorId = user.getId().toString();

        log.info("Requesting in-chat payment dispatch for {} by actor={}", dto.getCustomerWaId(), actorId);
        WhatsAppOrderResponseDto response = paymentService.createAndSendPayment(tenantId, dto, actorId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/orders")
    public ResponseEntity<Page<WhatsAppOrderResponseDto>> listOrders(
        @AuthenticationPrincipal String email,
        @RequestParam(value = "status", required = false) WhatsAppOrderPaymentStatus status,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        UUID tenantId = getTenantId(email);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(paymentService.listOrders(tenantId, status, pageable));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<WhatsAppOrderResponseDto> getOrderById(
        @AuthenticationPrincipal String email,
        @PathVariable("orderId") UUID orderId
    ) {
        UUID tenantId = getTenantId(email);
        return ResponseEntity.ok(paymentService.getOrderById(tenantId, orderId));
    }

    @GetMapping("/orders/ref/{referenceId}")
    public ResponseEntity<WhatsAppOrderResponseDto> getOrderByReference(
        @AuthenticationPrincipal String email,
        @PathVariable("referenceId") String referenceId
    ) {
        UUID tenantId = getTenantId(email);
        return ResponseEntity.ok(paymentService.getOrderByReference(tenantId, referenceId));
    }

    @PostMapping("/orders/{orderId}/resend")
    public ResponseEntity<WhatsAppOrderResponseDto> resendPayment(
        @AuthenticationPrincipal String email,
        @PathVariable("orderId") UUID orderId
    ) {
        User user = getUser(email);
        UUID tenantId = user.getTenant().getId();
        return ResponseEntity.ok(paymentService.resendPayment(tenantId, orderId, user.getId().toString()));
    }

    @PostMapping("/orders/{orderId}/refund")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN') or isAuthenticated()")
    public ResponseEntity<RefundResult> refundOrder(
        @AuthenticationPrincipal String email,
        @PathVariable("orderId") UUID orderId,
        @RequestBody Map<String, Object> refundPayload
    ) {
        User user = getUser(email);
        UUID tenantId = user.getTenant().getId();

        Long amountMinor = refundPayload.containsKey("amountMinor")
            ? ((Number) refundPayload.get("amountMinor")).longValue()
            : null;
        String reason = (String) refundPayload.getOrDefault("reason", "Customer requested refund");

        RefundResult result = paymentService.refundOrder(tenantId, orderId, amountMinor, reason, user.getId().toString());
        return ResponseEntity.ok(result);
    }

    private User getUser(String email) {
        if (email == null) {
            throw new IllegalStateException("Unauthenticated request: user email is null");
        }
        return userRepository.findByEmailWithTenant(email)
            .orElseThrow(() -> new IllegalStateException("User not found: " + email));
    }

    private UUID getTenantId(String email) {
        return getUser(email).getTenant().getId();
    }
}
