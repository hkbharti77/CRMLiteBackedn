package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.models.TenantSubscription.BillingCycle;
import com.chatcrmlite.backend.models.BillingTransaction.TransactionStatus;
import com.chatcrmlite.backend.models.BillingTransaction.PaymentGateway;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.payment.RazorpayPaymentService;
import com.chatcrmlite.backend.services.payment.StripePaymentService;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import com.chatcrmlite.backend.event.TenantSubscriptionUpdatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookController {

    private final StripePaymentService stripePaymentService;
    private final RazorpayPaymentService razorpayPaymentService;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final BillingTransactionRepository billingTransactionRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final QuotaEnforcerService quotaEnforcerService;
    private final LeadRepository leadRepository;
    private final BookingRepository bookingRepository;
    private final AppointmentRepository appointmentRepository;
    private final TicketRepository ticketRepository;
    private final CustomEmailRepository customEmailRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${razorpay.webhook.secret}")
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

    @PostMapping("/api/v1/billing/checkout")
    public ResponseEntity<?> initiateUpgrade(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> request) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        UUID tenantId = user.getTenant().getId();
        String planId = request.get("planId");
        String billingCycleStr = request.get("billingCycle"); // MONTHLY or YEARLY
        String gatewayStr = request.get("gateway"); // STRIPE or RAZORPAY

        if (planId == null || billingCycleStr == null || gatewayStr == null) {
            return ResponseEntity.badRequest().body("planId, billingCycle, and gateway are required.");
        }

        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Subscription plan not found: " + planId));

        BigDecimal price = billingCycleStr.equalsIgnoreCase("YEARLY") ? plan.getPriceYearly() : plan.getPriceMonthly();
        
        // --- PRORATION LOGIC ---
        try {
            TenantSubscription currentSub = quotaEnforcerService.getActiveSubscription(tenantId);
            if (currentSub != null && currentSub.getStatus() == SubscriptionStatus.ACTIVE && !"FREE".equalsIgnoreCase(currentSub.getPlan().getId())) {
                LocalDateTime start = currentSub.getCurrentPeriodStart();
                LocalDateTime end = currentSub.getCurrentPeriodEnd();
                LocalDateTime now = LocalDateTime.now();

                if (now.isBefore(end)) {
                    long totalDays = Duration.between(start, end).toDays();
                    if (totalDays > 0) {
                        long remainingDays = Duration.between(now, end).toDays();
                        BigDecimal currentPlanPrice = currentSub.getBillingCycle() == BillingCycle.YEARLY ? 
                                currentSub.getPlan().getPriceYearly() : currentSub.getPlan().getPriceMonthly();

                        BigDecimal unusedValue = currentPlanPrice
                                .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(remainingDays));

                        log.info("📊 Proration calculated - totalDays: {}, remainingDays: {}, currentPlanPrice: {}, unusedValue: {}", 
                                totalDays, remainingDays, currentPlanPrice, unusedValue);

                        price = price.subtract(unusedValue);
                        if (price.compareTo(BigDecimal.ONE) < 0) {
                            price = BigDecimal.ONE; // Minimum transaction amount
                            log.info("📊 Final price clamped to minimum amount: {}", price);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Could not calculate proration for tenant {}: {}", tenantId, e.getMessage());
        }
        // --- END PRORATION LOGIC ---

        String currency = "INR"; // Default currency

        log.info("💰 Initiating checkout for tenant: {}, plan: {}, gateway: {}, finalPrice: {}", tenantId, planId, gatewayStr, price);

        try {
            if (gatewayStr.equalsIgnoreCase("STRIPE")) {
                String checkoutUrl = stripePaymentService.createCheckoutSession(
                        tenantId, plan.getId(), billingCycleStr.toUpperCase(), price, currency);
                
                Map<String, String> response = new HashMap<>();
                response.put("checkoutUrl", checkoutUrl);
                response.put("gateway", "STRIPE");
                return ResponseEntity.ok(response);
            } else if (gatewayStr.equalsIgnoreCase("RAZORPAY")) {
                // Generate a unique transaction/receipt ID
                String receiptId = "rcpt_" + tenantId.toString().substring(0, 8) + "_" + System.currentTimeMillis() % 100000;
                String orderId = razorpayPaymentService.createOrder(price, currency, receiptId);

                // Save pending transaction record
                BillingTransaction transaction = BillingTransaction.builder()
                        .amount(price)
                        .currency(currency)
                        .status(TransactionStatus.PENDING)
                        .paymentGateway(PaymentGateway.RAZORPAY)
                        .gatewayTransactionId(orderId)
                        .tenant(user.getTenant())
                        .build();
                billingTransactionRepository.save(transaction);

                Map<String, Object> response = new HashMap<>();
                response.put("orderId", orderId);
                response.put("amount", price.multiply(BigDecimal.valueOf(100)).intValue()); // in paise for frontend SDK
                response.put("currency", currency);
                response.put("gateway", "RAZORPAY");
                response.put("keyId", System.getenv("RAZORPAY_KEY_ID")); // pass key id for SDK initialization
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body("Unsupported gateway: " + gatewayStr);
            }
        } catch (Exception e) {
            log.error("❌ Checkout session initiation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    /**
     * Webhook receiver for Stripe payments.
     */
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
                log.info("✅ Successful Stripe Checkout Session. Tenant: {}, Plan: {}", tenantId, planId);

                // Update tenant subscription status
                updateTenantSubscription(tenantId, planId, billingCycleStr, stripeSubId, null);
                
                // Save success transaction
                BigDecimal amount = BigDecimal.valueOf(sessionNode.get("amount_total").asDouble() / 100.0);
                String stripeTxId = sessionNode.get("id").asText();
                saveBillingTransaction(tenantId, amount, "USD", stripeTxId, PaymentGateway.STRIPE, TransactionStatus.SUCCESS);
            }

            return ResponseEntity.ok("Success");
        } catch (Exception e) {
            log.error("❌ Stripe webhook handling failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    /**
     * Webhook receiver for Razorpay payments.
     */
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
                BigDecimal amount = BigDecimal.valueOf(paymentNode.get("amount").asDouble() / 100.0); // convert paise to INR
                String currency = paymentNode.get("currency").asText();

                // Find pending transaction by order ID
                BillingTransaction transaction = billingTransactionRepository.findByGatewayTransactionId(orderId)
                        .orElse(null);

                if (transaction != null) {
                    UUID tenantId = transaction.getTenant().getId();
                    log.info("✅ Razorpay payment success. Tenant: {}, Order: {}", tenantId, orderId);

                    // Update transaction
                    transaction.setStatus(TransactionStatus.SUCCESS);
                    transaction.setGatewayTransactionId(paymentId);
                    billingTransactionRepository.save(transaction);

                    // Upgrade subscription to PRO plan since standard Indian purchases are PRO
                    updateTenantSubscription(tenantId, "PRO", "MONTHLY", null, orderId);
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

    private void updateTenantSubscription(UUID tenantId, String planId, String billingCycleStr, String stripeSubId, String razorpaySubId) {
        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Plan not found: " + planId));

        TenantSubscription sub = tenantSubscriptionRepository.findByTenantId(tenantId)
                .orElse(new TenantSubscription());

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        sub.setTenant(tenant);

        sub.setPlan(plan);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setBillingCycle(BillingCycle.valueOf(billingCycleStr.toUpperCase()));
        sub.setCurrentPeriodStart(LocalDateTime.now());
        
        int periodMonths = billingCycleStr.equalsIgnoreCase("YEARLY") ? 12 : 1;
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(periodMonths));

        if (stripeSubId != null) sub.setStripeSubscriptionId(stripeSubId);
        if (razorpaySubId != null) sub.setRazorpaySubscriptionId(razorpaySubId);

        tenantSubscriptionRepository.save(sub);

        // Sync legacy Tenant.planType field so profile-based plan checks are consistent
        tenantRepository.findById(tenantId).ifPresent(t -> {
            try {
                User.PlanType legacyPlan = User.PlanType.valueOf(planId.toUpperCase());
                t.setPlanType(legacyPlan);
                tenantRepository.save(t);
            } catch (IllegalArgumentException e) {
                log.warn("⚠️ Plan ID '{}' has no matching legacy PlanType enum — skipping Tenant.planType sync", planId);
            }
        });

        eventPublisher.publishEvent(new TenantSubscriptionUpdatedEvent(this, tenantId));
        log.info("✅ Tenant {} subscription updated to plan: {} until {}", tenantId, planId, sub.getCurrentPeriodEnd());
    }

    @PostMapping("/api/v1/billing/mock-success")
    public ResponseEntity<?> mockPaymentSuccess(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> request) {
        
        String orderId = request.get("orderId");
        if (orderId == null) {
            return ResponseEntity.badRequest().body("orderId is required.");
        }
        
        BillingTransaction transaction = billingTransactionRepository.findByGatewayTransactionId(orderId)
                .orElse(null);
        
        if (transaction == null) {
            return ResponseEntity.badRequest().body("Transaction not found for reference: " + orderId);
        }
        
        UUID tenantId = transaction.getTenant().getId();
        log.info("🧪 MOCK success trigger for tenant: {}, order: {}", tenantId, orderId);
        
        // Update transaction
        transaction.setStatus(TransactionStatus.SUCCESS);
        transaction.setGatewayTransactionId("mock_tx_" + System.currentTimeMillis());
        billingTransactionRepository.save(transaction);
        
        // Upgrade subscription (PRO vs ENTERPRISE based on price/amount)
        String planId = "PRO";
        if (transaction.getAmount().compareTo(BigDecimal.valueOf(5000.00)) > 0) {
            planId = "ENTERPRISE";
        }
        
        updateTenantSubscription(tenantId, planId, "MONTHLY", null, orderId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Mock payment processed successfully");
        response.put("plan", planId);
        return ResponseEntity.ok(response);
    }

    private void saveBillingTransaction(UUID tenantId, BigDecimal amount, String currency, String transactionId, PaymentGateway gateway, TransactionStatus status) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        BillingTransaction transaction = BillingTransaction.builder()
                .amount(amount)
                .currency(currency)
                .status(status)
                .paymentGateway(gateway)
                .gatewayTransactionId(transactionId)
                .tenant(tenant)
                .build();
        
        billingTransactionRepository.save(transaction);
    }
}
