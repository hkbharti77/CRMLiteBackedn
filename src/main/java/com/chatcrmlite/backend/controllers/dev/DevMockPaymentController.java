package com.chatcrmlite.backend.controllers.dev;

import com.chatcrmlite.backend.models.BillingTransaction;
import com.chatcrmlite.backend.models.BillingTransaction.PaymentGateway;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BillingTransactionRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.billing.SubscriptionBillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Development & Testing ONLY mock payment success endpoint.
 *
 * This controller is strictly registered ONLY under "dev", "test", or "local" Spring profiles.
 * In production (or any other profile), this bean is NOT registered in the Spring ApplicationContext,
 * causing all requests to fail closed with a 404 Not Found.
 */
@Slf4j
@RestController
@Profile({"dev", "test", "local"})
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class DevMockPaymentController {

    private final UserRepository userRepository;
    private final BillingTransactionRepository billingTransactionRepository;
    private final SubscriptionBillingService subscriptionBillingService;

    @PostMapping("/mock-success")
    public ResponseEntity<?> mockPaymentSuccess(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> request) {

        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        String orderId = request.get("orderId");
        if (orderId == null || orderId.isBlank()) {
            return ResponseEntity.badRequest().body("orderId is required.");
        }

        BillingTransaction transaction = billingTransactionRepository.findByGatewayTransactionId(orderId)
                .orElse(null);

        if (transaction == null) {
            return ResponseEntity.badRequest().body("Transaction not found for reference: " + orderId);
        }

        // Tenant Isolation Check: Verify transaction belongs to caller's tenant
        if (user.getTenant() == null || transaction.getTenant() == null 
                || !user.getTenant().getId().equals(transaction.getTenant().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Transaction does not belong to the caller's tenant");
        }

        UUID tenantId = transaction.getTenant().getId();
        log.info("🧪 [DEV/TEST ONLY] MOCK success trigger for tenant: {}, order: {}", tenantId, orderId);

        String planId = request.get("planId");
        if (planId == null || planId.isBlank()) {
            planId = "PRO";
            if (transaction.getAmount().compareTo(BigDecimal.valueOf(5000.00)) > 0) {
                planId = "ENTERPRISE";
            }
        }
        String billingCycleStr = request.getOrDefault("billingCycle", "MONTHLY");

        BillingTransaction updatedTx = subscriptionBillingService.processPaymentSuccess(
                tenantId,
                planId.toUpperCase(),
                billingCycleStr.toUpperCase(),
                transaction.getAmount(),
                transaction.getCurrency(),
                orderId,
                "mock_tx_" + System.currentTimeMillis(),
                PaymentGateway.RAZORPAY,
                user.getEmail()
        );

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Mock payment processed successfully");
        response.put("plan", planId.toUpperCase());
        response.put("transactionId", updatedTx.getId());
        return ResponseEntity.ok(response);
    }
}
