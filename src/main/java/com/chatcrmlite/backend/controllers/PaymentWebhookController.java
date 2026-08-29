package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.models.BillingTransaction.PaymentGateway;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.billing.SubscriptionBillingService;
import com.chatcrmlite.backend.services.payment.RazorpayPaymentService;
import com.chatcrmlite.backend.services.payment.StripePaymentService;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookController {

    private final SubscriptionBillingService subscriptionBillingService;
    private final StripePaymentService stripePaymentService;
    private final RazorpayPaymentService razorpayPaymentService;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final BillingTransactionRepository billingTransactionRepository;
    private final UserRepository userRepository;
    private final QuotaEnforcerService quotaEnforcerService;
    private final LeadRepository leadRepository;
    private final BookingRepository bookingRepository;
    private final AppointmentRepository appointmentRepository;
    private final TicketRepository ticketRepository;
    private final CustomEmailRepository customEmailRepository;
    private final ObjectMapper objectMapper;

    @Value("${razorpay.webhook.secret:dummy_razorpay_webhook_secret}")
    private String razorpayWebhookSecret;

    @GetMapping("/api/v1/billing/plans")
    public ResponseEntity<List<SubscriptionPlan>> getAvailablePlans(@RequestParam(required = false) String currency) {
        List<SubscriptionPlan> plans = subscriptionPlanRepository.findAll();
        if ("INR".equalsIgnoreCase(currency)) {
            plans.forEach(p -> {
                if (p.getPriceMonthlyInr() != null) p.setPriceMonthly(p.getPriceMonthlyInr());
                if (p.getPriceYearlyInr() != null) p.setPriceYearly(p.getPriceYearlyInr());
            });
        } else if ("USD".equalsIgnoreCase(currency)) {
            plans.forEach(p -> {
                if (p.getPriceMonthlyUsd() != null) p.setPriceMonthly(p.getPriceMonthlyUsd());
                if (p.getPriceYearlyUsd() != null) p.setPriceYearly(p.getPriceYearlyUsd());
            });
        }
        return ResponseEntity.ok(plans);
    }

    @GetMapping("/api/v1/billing/subscription")
    public ResponseEntity<?> getSubscriptionStatus(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        UUID tenantId = user.getTenant().getId();
        
        TenantSubscription sub = quotaEnforcerService.getActiveSubscription(tenantId);
        
        long employeesCount = userRepository.countByTenantId(tenantId);
        long leadsCount = leadRepository.countByTenantId(tenantId);
        long bookingsCount = bookingRepository.countByTenantId(tenantId);
        long appointmentsCount = appointmentRepository.countByTenantId(tenantId);
        long ticketsCount = ticketRepository.countActiveByTenantId(tenantId);
        
        LocalDateTime cycleStart = sub.getCurrentPeriodStart();
        long emailsCount = customEmailRepository.countSentEmailsSince(tenantId, cycleStart);

        Map<String, Object> response = new HashMap<>();
        response.put("planId", sub.getPlan().getId());
        response.put("planName", sub.getPlan().getName());
        response.put("status", sub.getStatus().toString());
        response.put("billingCycle", sub.getBillingCycle().toString());
        response.put("currentPeriodStart", sub.getCurrentPeriodStart());
        response.put("currentPeriodEnd", sub.getCurrentPeriodEnd());
        response.put("primaryResource", user.getTenant().getPrimaryResource().toString());
        
        Map<String, Object> limits = new HashMap<>();
        limits.put("employeeLimit", sub.getPlan().getEmployeeLimit());
        limits.put("primaryResourceLimit", sub.getPlan().getPrimaryResourceLimit());
        limits.put("secondaryResourceLimit", sub.getPlan().getSecondaryResourceLimit());
        limits.put("ticketLimit", sub.getPlan().getTicketLimit());
        limits.put("emailLimit", sub.getPlan().getEmailLimit());
        limits.put("hasWhatsapp", sub.getPlan().isHasWhatsapp());
        limits.put("hasCustomWidget", sub.getPlan().isHasCustomWidget());
        limits.put("hasRagLlm", sub.getPlan().isHasRagLlm());
        response.put("limits", limits);

        Map<String, Object> usage = new HashMap<>();
        usage.put("employeesCount", employeesCount);
        usage.put("leadsCount", leadsCount);
        usage.put("bookingsCount", bookingsCount);
        usage.put("appointmentsCount", appointmentsCount);
        usage.put("ticketsCount", ticketsCount);
        usage.put("emailsCount", emailsCount);
        response.put("usage", usage);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/billing/transactions")
    public ResponseEntity<?> getBillingTransactions(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        UUID tenantId = user.getTenant().getId();
        
        List<BillingTransaction> transactions = billingTransactionRepository.findByTenantId(tenantId);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping(value = "/api/v1/billing/invoice/{transactionId}/download", produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> downloadInvoice(
            @PathVariable UUID transactionId,
            @AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        String invoiceHtml = subscriptionBillingService.generateInvoiceHtml(transactionId, user);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.TEXT_HTML)
                .body(invoiceHtml);
    }

    @PostMapping("/api/v1/billing/invoice/{transactionId}/resend")
    public ResponseEntity<?> resendInvoice(
            @PathVariable UUID transactionId,
            @AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        subscriptionBillingService.resendInvoiceEmail(transactionId, user);
        return ResponseEntity.ok(Map.of("success", true, "message", "Invoice email queued for dispatch."));
    }

    @PostMapping("/api/v1/billing/checkout")
    public ResponseEntity<?> initiateUpgrade(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> request) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        try {
            Map<String, Object> checkoutData = subscriptionBillingService.initiateCheckout(user, request);
            return ResponseEntity.ok(checkoutData);
        } catch (org.springframework.web.server.ResponseStatusException rse) {
            return ResponseEntity.status(rse.getStatusCode()).body(rse.getReason());
        } catch (Exception e) {
            log.error("❌ Checkout initiation failed for user: {}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/api/v1/billing/verify-razorpay")
    public ResponseEntity<?> verifyRazorpayPayment(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> request) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        try {
            Map<String, Object> verifyData = subscriptionBillingService.verifyRazorpayPayment(user, request);
            return ResponseEntity.ok(verifyData);
        } catch (org.springframework.web.server.ResponseStatusException rse) {
            return ResponseEntity.status(rse.getStatusCode()).body(rse.getReason());
        } catch (Exception e) {
            log.error("❌ Razorpay verification failed for user: {}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/api/v1/public/webhooks/stripe")
    public ResponseEntity<String> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        log.info("📨 Received Stripe Webhook event");

        if (!stripePaymentService.verifyWebhookSignature(payload, sigHeader)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        }

        try {
            JsonNode eventNode = objectMapper.readTree(payload);
            String eventType = eventNode.get("type").asText();
            log.info("Stripe Webhook Event Type: {}", eventType);

            if ("checkout.session.completed".equals(eventType)) {
                JsonNode sessionNode = eventNode.get("data").get("object");
                JsonNode metadataNode = sessionNode.get("metadata");
                
                String tenantIdStr = metadataNode.get("tenantId").asText();
                String planId = metadataNode.get("planId").asText();
                String billingCycleStr = metadataNode.get("billingCycle").asText();
                String stripeSubId = sessionNode.has("subscription") ? sessionNode.get("subscription").asText() : null;

                UUID tenantId = UUID.fromString(tenantIdStr);
                BigDecimal amount = BigDecimal.valueOf(sessionNode.get("amount_total").asDouble() / 100.0);
                String stripeTxId = sessionNode.get("id").asText();

                log.info("✅ Successful Stripe Checkout Session. Tenant: {}, Plan: {}", tenantId, planId);

                subscriptionBillingService.processPaymentSuccess(
                        tenantId, planId, billingCycleStr, amount, "USD", stripeTxId, stripeSubId, PaymentGateway.STRIPE, null);
            }

            return ResponseEntity.ok("Success");
        } catch (Exception e) {
            log.error("❌ Stripe webhook handling failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PostMapping("/api/v1/public/webhooks/razorpay")
    public ResponseEntity<String> handleRazorpayWebhook(
            @RequestBody String payload,
            @RequestHeader("X-Razorpay-Signature") String signature) {

        log.info("📨 Received Razorpay Webhook event");

        if (!razorpayPaymentService.verifySignature(payload, signature, razorpayWebhookSecret)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        }

        try {
            JsonNode eventNode = objectMapper.readTree(payload);
            String event = eventNode.get("event").asText();
            log.info("Razorpay Webhook Event: {}", event);

            if ("order.paid".equals(event) || "payment.captured".equals(event)) {
                JsonNode paymentNode = eventNode.get("payload").get("payment").get("object");
                String orderId = paymentNode.get("order_id").asText();
                String paymentId = paymentNode.get("id").asText();
                BigDecimal amount = BigDecimal.valueOf(paymentNode.get("amount").asDouble() / 100.0);
                String currency = paymentNode.get("currency").asText();

                BillingTransaction transaction = billingTransactionRepository.findByGatewayTransactionId(orderId)
                        .orElse(null);

                if (transaction != null) {
                    UUID tenantId = transaction.getTenant().getId();
                    log.info("✅ Razorpay payment success. Tenant: {}, Order: {}", tenantId, orderId);

                    subscriptionBillingService.processPaymentSuccess(
                            tenantId, "PRO", "MONTHLY", amount, currency, orderId, paymentId, PaymentGateway.RAZORPAY, null);
                } else {
                    log.warn("⚠️ Transaction not found in db for Razorpay order ID: {}", orderId);
                }
            }

            return ResponseEntity.ok("Success");
        } catch (Exception e) {
            log.error("❌ Razorpay webhook handling failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @GetMapping("/api/v1/public/payment/success")
    public ResponseEntity<String> paymentSuccess(@RequestParam(value = "session_id", required = false) String sessionId) {
        String html = "<html><head><title>Payment Successful</title>"
                + "<style>"
                + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background-color: #F8FAFC; }"
                + ".card { background: white; padding: 40px; border-radius: 16px; box-shadow: 0 4px 20px rgba(0,0,0,0.05); text-align: center; max-width: 400px; width: 90%; }"
                + ".icon { font-size: 64px; color: #10B981; margin-bottom: 20px; }"
                + "h1 { color: #0F172A; margin: 0 0 10px 0; font-size: 24px; font-weight: 700; }"
                + "p { color: #64748B; font-size: 16px; margin: 0 0 24px 0; line-height: 1.5; }"
                + ".btn { background-color: #0F766E; color: white; border: none; padding: 12px 24px; border-radius: 8px; font-size: 16px; font-weight: 600; cursor: pointer; text-decoration: none; display: inline-block; transition: background-color 0.2s; }"
                + ".btn:hover { background-color: #0D5C56; }"
                + "</style></head><body>"
                + "<div class='card'>"
                + "<div class='icon'>✓</div>"
                + "<h1>Payment Successful!</h1>"
                + "<p>Thank you for your purchase. Your subscription is being activated. You can now close this tab and return to the CRMLite app.</p>"
                + "<a href='#' class='btn' onclick='window.close(); return false;'>Close Window</a>"
                + "</div>"
                + "</body></html>";
        return ResponseEntity.ok()
                .header("Content-Type", "text/html")
                .body(html);
    }

    @GetMapping("/api/v1/public/payment/cancel")
    public ResponseEntity<String> paymentCancel() {
        String html = "<html><head><title>Payment Cancelled</title>"
                + "<style>"
                + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background-color: #F8FAFC; }"
                + ".card { background: white; padding: 40px; border-radius: 16px; box-shadow: 0 4px 20px rgba(0,0,0,0.05); text-align: center; max-width: 400px; width: 90%; }"
                + ".icon { font-size: 64px; color: #EF4444; margin-bottom: 20px; }"
                + "h1 { color: #0F172A; margin: 0 0 10px 0; font-size: 24px; font-weight: 700; }"
                + "p { color: #64748B; font-size: 16px; margin: 0 0 24px 0; line-height: 1.5; }"
                + ".btn { background-color: #64748B; color: white; border: none; padding: 12px 24px; border-radius: 8px; font-size: 16px; font-weight: 600; cursor: pointer; text-decoration: none; display: inline-block; transition: background-color 0.2s; }"
                + ".btn:hover { background-color: #475569; }"
                + "</style></head><body>"
                + "<div class='card'>"
                + "<div class='icon'>✕</div>"
                + "<h1>Payment Cancelled</h1>"
                + "<p>The checkout process was cancelled. No charges were made. You can safely close this window.</p>"
                + "<a href='#' class='btn' onclick='window.close(); return false;'>Close Window</a>"
                + "</div>"
                + "</body></html>";
        return ResponseEntity.ok()
                .header("Content-Type", "text/html")
                .body(html);
    }
}
