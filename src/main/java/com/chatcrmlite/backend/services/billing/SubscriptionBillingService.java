package com.chatcrmlite.backend.services.billing;

import com.chatcrmlite.backend.event.PlanPaymentSuccessEvent;
import com.chatcrmlite.backend.event.TenantSubscriptionUpdatedEvent;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.models.BillingTransaction.InvoiceEmailStatus;
import com.chatcrmlite.backend.models.BillingTransaction.PaymentGateway;
import com.chatcrmlite.backend.models.BillingTransaction.TransactionStatus;
import com.chatcrmlite.backend.models.TenantSubscription.BillingCycle;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.payment.RazorpayPaymentService;
import com.chatcrmlite.backend.services.payment.StripePaymentService;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionBillingService {

    private final StripePaymentService stripePaymentService;
    private final RazorpayPaymentService razorpayPaymentService;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final BillingTransactionRepository billingTransactionRepository;
    private final TenantRepository tenantRepository;
    private final QuotaEnforcerService quotaEnforcerService;
    private final ApplicationEventPublisher eventPublisher;

    public int getPlanLevel(String planId) {
        if (planId == null) return 0;
        switch (planId.toUpperCase()) {
            case "ENTERPRISE": return 2;
            case "PRO": return 1;
            case "FREE": default: return 0;
        }
    }

    public void validateTierAndRole(User user, String targetPlanId) {
        if (user == null || user.getRole() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User authentication required.");
        }

        // Role validation: OWNER, ADMIN, SUPER_ADMIN, PLATFORM_ADMIN or Super Admin email
        String roleStr = user.getRole() != null ? user.getRole().name().toUpperCase() : "";
        String cleanEmail = user.getEmail() != null ? user.getEmail().toLowerCase().trim() : "";

        boolean isSuperAdmin = roleStr.contains("SUPER") || roleStr.contains("PLATFORM") || cleanEmail.equals("gyanvaniai@gmail.com") || cleanEmail.startsWith("superadmin");
        boolean isOwnerOrAdmin = user.getRole() == User.Role.OWNER || user.getRole() == User.Role.ADMIN || isSuperAdmin;

        if (!isOwnerOrAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only workspace Owner, Admin, or Super Admin can purchase or upgrade subscription plans.");
        }

        UUID tenantId = user.getTenant().getId();
        TenantSubscription currentSub = quotaEnforcerService.getActiveSubscription(tenantId);

        if (currentSub != null && currentSub.getStatus() == SubscriptionStatus.ACTIVE && currentSub.getPlan() != null) {
            int currentTier = getPlanLevel(currentSub.getPlan().getId());
            int targetTier = getPlanLevel(targetPlanId);

            if (targetTier < currentTier) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                        "Downgrades are not allowed. You are currently on the higher " + currentSub.getPlan().getName() + " tier.");
            }
            if (targetTier == currentTier) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                        "This plan is already active. You are currently subscribed to " + currentSub.getPlan().getName() + ".");
            }
        }
    }

    public Map<String, Object> initiateCheckout(User user, Map<String, String> request) {
        UUID tenantId = user.getTenant().getId();
        String planId = request.get("planId");
        String billingCycleStr = request.getOrDefault("billingCycle", "MONTHLY");
        String gatewayStr = request.getOrDefault("gateway", "RAZORPAY");
        String currencyStr = request.getOrDefault("currency", gatewayStr.equalsIgnoreCase("RAZORPAY") ? "INR" : "USD");

        if (planId == null || planId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planId is required.");
        }

        // 1. Validate Tier Comparison & User Authorization Role
        validateTierAndRole(user, planId);

        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId.toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription plan not found: " + planId));

        // 2. Server-side price resolution
        BigDecimal price;
        if ("INR".equalsIgnoreCase(currencyStr)) {
            price = billingCycleStr.equalsIgnoreCase("YEARLY") ? 
                    (plan.getPriceYearlyInr() != null ? plan.getPriceYearlyInr() : plan.getPriceYearly()) : 
                    (plan.getPriceMonthlyInr() != null ? plan.getPriceMonthlyInr() : plan.getPriceMonthly());
        } else {
            price = billingCycleStr.equalsIgnoreCase("YEARLY") ? 
                    (plan.getPriceYearlyUsd() != null ? plan.getPriceYearlyUsd() : plan.getPriceYearly()) : 
                    (plan.getPriceMonthlyUsd() != null ? plan.getPriceMonthlyUsd() : plan.getPriceMonthly());
        }

        // 3. Server-side Proration calculation for active paid plans
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
                        BigDecimal currentPlanPrice;
                        if ("INR".equalsIgnoreCase(currencyStr)) {
                            currentPlanPrice = currentSub.getBillingCycle() == BillingCycle.YEARLY ? 
                                    (currentSub.getPlan().getPriceYearlyInr() != null ? currentSub.getPlan().getPriceYearlyInr() : currentSub.getPlan().getPriceYearly()) : 
                                    (currentSub.getPlan().getPriceMonthlyInr() != null ? currentSub.getPlan().getPriceMonthlyInr() : currentSub.getPlan().getPriceMonthly());
                        } else {
                            currentPlanPrice = currentSub.getBillingCycle() == BillingCycle.YEARLY ? 
                                    (currentSub.getPlan().getPriceYearlyUsd() != null ? currentSub.getPlan().getPriceYearlyUsd() : currentSub.getPlan().getPriceYearly()) : 
                                    (currentSub.getPlan().getPriceMonthlyUsd() != null ? currentSub.getPlan().getPriceMonthlyUsd() : currentSub.getPlan().getPriceMonthly());
                        }

                        BigDecimal unusedValue = currentPlanPrice
                                .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(remainingDays));

                        log.info("📊 Proration calculated for tenant {}: totalDays={}, remainingDays={}, unusedValue={}", 
                                tenantId, totalDays, remainingDays, unusedValue);

                        price = price.subtract(unusedValue);
                        if (price.compareTo(BigDecimal.ONE) < 0) {
                            price = BigDecimal.ONE;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Proration calculation error for tenant {}: {}", tenantId, e.getMessage());
        }

        log.info("💰 Initiating checkout: tenant={}, plan={}, gateway={}, currency={}, finalPrice={}", 
                tenantId, planId, gatewayStr, currencyStr, price);

        Map<String, Object> response = new HashMap<>();

        if (gatewayStr.equalsIgnoreCase("STRIPE")) {
            String checkoutUrl = stripePaymentService.createCheckoutSession(
                    tenantId, plan.getId(), billingCycleStr.toUpperCase(), price, currencyStr);
            
            response.put("checkoutUrl", checkoutUrl);
            response.put("gateway", "STRIPE");
            response.put("planId", plan.getId());
            response.put("planName", plan.getName());
            return response;
        } else if (gatewayStr.equalsIgnoreCase("RAZORPAY")) {
            String receiptId = "rcpt_" + tenantId.toString().substring(0, 8) + "_" + (System.currentTimeMillis() % 100000);
            String orderId = razorpayPaymentService.createOrder(price, currencyStr, receiptId);

            // Save pending transaction record
            BillingTransaction transaction = BillingTransaction.builder()
                    .amount(price)
                    .currency(currencyStr)
                    .status(TransactionStatus.PENDING)
                    .paymentGateway(PaymentGateway.RAZORPAY)
                    .gatewayTransactionId(orderId)
                    .invoiceEmailStatus(InvoiceEmailStatus.PENDING)
                    .tenant(user.getTenant())
                    .build();
            billingTransactionRepository.save(transaction);

            String resolvedKeyId = razorpayPaymentService.getKeyId();

            response.put("orderId", orderId);
            response.put("amount", price.multiply(BigDecimal.valueOf(100)).intValue()); // paise for frontend SDK
            response.put("currency", currencyStr);
            response.put("gateway", "RAZORPAY");
            response.put("keyId", resolvedKeyId);
            response.put("planId", plan.getId());
            response.put("planName", plan.getName());
            return response;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment gateway: " + gatewayStr);
        }
    }

    public Map<String, Object> verifyRazorpayPayment(User user, Map<String, String> request) {
        String orderId = request.get("razorpayOrderId");
        String paymentId = request.get("razorpayPaymentId");
        String signature = request.get("razorpaySignature");
        String planId = request.get("planId");
        String billingCycleStr = request.getOrDefault("billingCycle", "MONTHLY");

        if (orderId == null || paymentId == null || signature == null || planId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "razorpayOrderId, razorpayPaymentId, razorpaySignature, and planId are required.");
        }

        boolean isValid = razorpayPaymentService.verifyPaymentSignature(orderId, paymentId, signature);
        if (!isValid) {
            log.warn("⚠️ Invalid Razorpay signature for tenant: {}, orderId: {}", user.getTenant().getId(), orderId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment signature verification.");
        }

        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId.toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found: " + planId));

        BigDecimal price = billingCycleStr.equalsIgnoreCase("YEARLY") ? 
                (plan.getPriceYearlyInr() != null ? plan.getPriceYearlyInr() : plan.getPriceYearly()) : 
                (plan.getPriceMonthlyInr() != null ? plan.getPriceMonthlyInr() : plan.getPriceMonthly());

        BillingTransaction transaction = processPaymentSuccess(
                user.getTenant().getId(),
                planId.toUpperCase(),
                billingCycleStr.toUpperCase(),
                price,
                "INR",
                orderId,
                paymentId,
                PaymentGateway.RAZORPAY,
                user.getEmail()
        );

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Payment verified and subscription activated successfully!");
        response.put("planId", planId.toUpperCase());
        response.put("transactionId", transaction.getId());
        return response;
    }

    @Transactional
    public BillingTransaction processPaymentSuccess(
            UUID tenantId,
            String planId,
            String billingCycleStr,
            BigDecimal amount,
            String currency,
            String orderId,
            String paymentId,
            PaymentGateway gateway,
            String userEmail) {

        String resolvedGatewayTxId = paymentId != null ? paymentId : orderId;

        // Payment Idempotency Check: if transaction by orderId or paymentId is already SUCCESS, skip duplicate subscription update
        BillingTransaction transaction = null;
        if (orderId != null) {
            transaction = billingTransactionRepository.findByGatewayTransactionId(orderId).orElse(null);
        }
        if (transaction == null && paymentId != null) {
            transaction = billingTransactionRepository.findByGatewayTransactionId(paymentId).orElse(null);
        }

        if (transaction != null && transaction.getStatus() == TransactionStatus.SUCCESS) {
            log.info("ℹ️ Payment transaction {} for order {} already SUCCESS. Skipping duplicate processing.", 
                    transaction.getId(), orderId);
            return transaction;
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant not found: " + tenantId));

        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Plan not found: " + planId));

        // Update Tenant Subscription
        TenantSubscription sub = tenantSubscriptionRepository.findByTenantId(tenantId)
                .orElse(new TenantSubscription());

        sub.setTenant(tenant);
        sub.setPlan(plan);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setBillingCycle(BillingCycle.valueOf(billingCycleStr.toUpperCase()));
        sub.setCurrentPeriodStart(LocalDateTime.now());
        
        int periodMonths = billingCycleStr.equalsIgnoreCase("YEARLY") ? 12 : 1;
        sub.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(periodMonths));

        if (gateway == PaymentGateway.STRIPE) {
            sub.setStripeSubscriptionId(resolvedGatewayTxId);
        } else {
            sub.setRazorpaySubscriptionId(resolvedGatewayTxId);
        }

        tenantSubscriptionRepository.save(sub);

        // Sync legacy Tenant.planType
        try {
            User.PlanType legacyPlan = User.PlanType.valueOf(planId.toUpperCase());
            tenant.setPlanType(legacyPlan);
            tenantRepository.save(tenant);
        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Plan ID '{}' has no matching legacy PlanType enum", planId);
        }

        // Create or update BillingTransaction
        if (transaction == null) {
            transaction = BillingTransaction.builder()
                    .amount(amount != null ? amount : BigDecimal.ZERO)
                    .currency(currency != null ? currency : "INR")
                    .status(TransactionStatus.SUCCESS)
                    .paymentGateway(gateway)
                    .gatewayTransactionId(resolvedGatewayTxId)
                    .invoiceEmailStatus(InvoiceEmailStatus.PENDING)
                    .tenant(tenant)
                    .build();
        } else {
            transaction.setStatus(TransactionStatus.SUCCESS);
            if (paymentId != null) {
                transaction.setGatewayTransactionId(paymentId);
            }
            if (transaction.getInvoiceEmailStatus() == null) {
                transaction.setInvoiceEmailStatus(InvoiceEmailStatus.PENDING);
            }
        }
        BillingTransaction savedTx = billingTransactionRepository.save(transaction);

        // Publish event for cache invalidation
        eventPublisher.publishEvent(new TenantSubscriptionUpdatedEvent(this, tenantId));

        // Publish event for AFTER_COMMIT invoice email dispatch
        eventPublisher.publishEvent(new PlanPaymentSuccessEvent(this, tenantId, savedTx.getId(), userEmail));

        log.info("✅ Tenant {} subscription successfully updated to {} until {}. Tx ID: {}", 
                tenantId, planId, sub.getCurrentPeriodEnd(), savedTx.getId());

        return savedTx;
    }

    public String generateInvoiceHtml(UUID transactionId, User user) {
        if (user == null || user.getTenant() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User authentication required.");
        }

        BillingTransaction tx = billingTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Billing transaction receipt not found: " + transactionId));

        // Security check: tenant matching or super admin
        String roleStr = user.getRole() != null ? user.getRole().name().toUpperCase() : "";
        String cleanEmail = user.getEmail() != null ? user.getEmail().toLowerCase().trim() : "";
        boolean isSuperAdmin = roleStr.contains("SUPER") || roleStr.contains("PLATFORM") || cleanEmail.equals("gyanvaniai@gmail.com") || cleanEmail.startsWith("superadmin");

        if (!isSuperAdmin && !tx.getTenant().getId().equals(user.getTenant().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to this billing transaction receipt.");
        }

        Tenant tenant = tx.getTenant();
        TenantSubscription sub = tenantSubscriptionRepository.findByTenantId(tenant.getId()).orElse(null);
        SubscriptionPlan plan = (sub != null && sub.getPlan() != null) ? sub.getPlan() : null;

        String planName = plan != null ? plan.getName() : "Subscription Plan";
        String tierId = plan != null ? plan.getId().toUpperCase() : "PRO";
        String billingCycle = (sub != null && sub.getBillingCycle() != null) ? sub.getBillingCycle().name() : "MONTHLY";
        
        String createdDate = tx.getCreatedAt() != null 
                ? tx.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("MMMM dd, yyyy - HH:mm:ss"))
                : java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM dd, yyyy"));

        String invoiceNo = "INV-" + (tx.getCreatedAt() != null ? tx.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) : "2026") + "-" + tx.getId().toString().substring(0, 8).toUpperCase();
        String formattedAmount = (tx.getCurrency() != null ? tx.getCurrency() : "INR") + " " + String.format("%.2f", tx.getAmount() != null ? tx.getAmount() : java.math.BigDecimal.ZERO);
        String periodText = (sub != null && sub.getCurrentPeriodStart() != null && sub.getCurrentPeriodEnd() != null)
                ? sub.getCurrentPeriodStart().format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy")) + " to " + sub.getCurrentPeriodEnd().format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy"))
                : "Active Subscription";

        int staffLimit = plan != null ? plan.getEmployeeLimit() : 10;
        int leadLimit = plan != null ? plan.getPrimaryResourceLimit() : 25000;
        int emailLimit = plan != null ? plan.getEmailLimit() : 15000;
        boolean hasWhatsapp = plan == null || plan.isHasWhatsapp();
        boolean hasAi = plan != null && plan.isHasRagLlm();
        boolean hasMultiCurrency = true;

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\"/><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n");
        sb.append("<title>Official Tax Invoice - ").append(invoiceNo).append("</title>\n");
        sb.append("<style>\n");
        sb.append("  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; margin: 0; padding: 0; background-color: #f8fafc; color: #0f172a; }\n");
        sb.append("  .action-bar { background: #0f172a; color: #ffffff; padding: 12px 24px; display: flex; justify-content: space-between; align-items: center; box-shadow: 0 2px 8px rgba(0,0,0,0.15); }\n");
        sb.append("  .btn-print { background: #6366f1; color: #ffffff; border: none; padding: 8px 20px; border-radius: 8px; font-weight: 700; font-size: 13px; cursor: pointer; text-decoration: none; transition: background 0.2s; }\n");
        sb.append("  .btn-print:hover { background: #4f46e5; }\n");
        sb.append("  .container { max-width: 800px; margin: 32px auto; background: #ffffff; border-radius: 16px; border: 1px solid #e2e8f0; padding: 40px; box-shadow: 0 10px 30px -5px rgba(0,0,0,0.05); }\n");
        sb.append("  .header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 2px solid #f1f5f9; padding-bottom: 24px; margin-bottom: 28px; }\n");
        sb.append("  .brand-logo { font-size: 24px; font-weight: 900; color: #4f46e5; letter-spacing: -0.5px; margin: 0; }\n");
        sb.append("  .brand-sub { font-size: 12px; color: #64748b; margin-top: 4px; font-weight: 500; }\n");
        sb.append("  .inv-details { text-align: right; }\n");
        sb.append("  .inv-title { font-size: 20px; font-weight: 800; color: #0f172a; text-transform: uppercase; letter-spacing: 1px; margin: 0; }\n");
        sb.append("  .inv-num { font-family: monospace; font-size: 14px; font-weight: 700; color: #6366f1; margin-top: 4px; }\n");
        sb.append("  .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-bottom: 28px; }\n");
        sb.append("  .box { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 18px; }\n");
        sb.append("  .box-label { font-size: 11px; text-transform: uppercase; font-weight: 700; color: #64748b; margin-bottom: 8px; letter-spacing: 0.5px; }\n");
        sb.append("  .box-text { font-size: 13px; font-weight: 600; color: #0f172a; margin: 3px 0; }\n");
        sb.append("  table { width: 100%; border-collapse: collapse; margin-top: 16px; margin-bottom: 28px; }\n");
        sb.append("  th { background: #f8fafc; text-align: left; padding: 12px 16px; font-size: 11px; text-transform: uppercase; color: #64748b; font-weight: 700; border-bottom: 2px solid #e2e8f0; }\n");
        sb.append("  td { padding: 16px; border-bottom: 1px solid #f1f5f9; font-size: 13px; color: #334155; }\n");
        sb.append("  .total-row td { background: #f1f5f9; font-weight: 800; font-size: 15px; color: #0f172a; border-top: 2px solid #cbd5e1; }\n");
        sb.append("  .badge-success { background: #ecfdf5; color: #059669; border: 1px solid #a7f3d0; padding: 3px 10px; border-radius: 20px; font-size: 11px; font-weight: 700; text-transform: uppercase; display: inline-block; }\n");
        sb.append("  .features-section { background: #faf5ff; border: 1px solid #e9d5ff; border-radius: 12px; padding: 20px; margin-bottom: 28px; }\n");
        sb.append("  .features-title { font-size: 13px; font-weight: 700; color: #7e22ce; margin: 0 0 12px 0; text-transform: uppercase; letter-spacing: 0.5px; }\n");
        sb.append("  .features-list { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; font-size: 12px; font-weight: 600; color: #6b21a8; }\n");
        sb.append("  .footer { border-top: 1px solid #e2e8f0; padding-top: 24px; text-align: center; font-size: 12px; color: #94a3b8; }\n");
        sb.append("  @media print {\n");
        sb.append("    .action-bar { display: none !important; }\n");
        sb.append("    .container { margin: 0; border: none; box-shadow: none; padding: 0; max-width: 100%; }\n");
        sb.append("    body { background: #ffffff; }\n");
        sb.append("  }\n");
        sb.append("</style>\n</head>\n<body>\n");
        sb.append("<div class=\"action-bar\">\n");
        sb.append("  <div style=\"font-weight:700;font-size:14px;\">📄 Official Receipt: ").append(invoiceNo).append("</div>\n");
        sb.append("  <button onclick=\"window.print()\" class=\"btn-print\">🖨️ Print / Save as PDF</button>\n");
        sb.append("</div>\n");
        sb.append("<div class=\"container\">\n");
        sb.append("  <div class=\"header\">\n");
        sb.append("    <div>\n");
        sb.append("      <h1 class=\"brand-logo\">GyanVani AI Connect</h1>\n");
        sb.append("      <div class=\"brand-sub\">GyanVani AI Connect • Official Billing Receipt</div>\n");
        sb.append("    </div>\n");
        sb.append("    <div class=\"inv-details\">\n");
        sb.append("      <div class=\"inv-title\">INVOICE</div>\n");
        sb.append("      <div class=\"inv-num\">").append(invoiceNo).append("</div>\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n");
        sb.append("  <div class=\"grid-2\">\n");
        sb.append("    <div class=\"box\">\n");
        sb.append("      <div class=\"box-label\">Billed To</div>\n");
        sb.append("      <div class=\"box-text\">").append(tenant.getBusinessName() != null ? tenant.getBusinessName() : "Workspace #" + tenant.getId()).append("</div>\n");
        sb.append("      <div class=\"box-text\" style=\"color:#64748b;font-weight:500;\">Email: ").append(user.getEmail()).append("</div>\n");
        sb.append("      <div class=\"box-text\" style=\"color:#64748b;font-size:11px;font-family:monospace;\">Tenant ID: ").append(tenant.getId()).append("</div>\n");
        sb.append("    </div>\n");
        sb.append("    <div class=\"box\">\n");
        sb.append("      <div class=\"box-label\">Payment Information</div>\n");
        sb.append("      <div class=\"box-text\">Gateway: ").append(tx.getPaymentGateway() != null ? tx.getPaymentGateway().name() : "RAZORPAY").append("</div>\n");
        sb.append("      <div class=\"box-text\">Status: <span class=\"badge-success\">").append(tx.getStatus()).append("</span></div>\n");
        sb.append("      <div class=\"box-text\" style=\"color:#64748b;font-size:11px;font-family:monospace;\">Gateway Ref: ").append(tx.getGatewayTransactionId() != null ? tx.getGatewayTransactionId() : "N/A").append("</div>\n");
        sb.append("      <div class=\"box-text\" style=\"color:#64748b;font-size:11px;\">Date: ").append(createdDate).append("</div>\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n");
        sb.append("  <table>\n");
        sb.append("    <thead>\n");
        sb.append("      <tr>\n");
        sb.append("        <th>Description & Item</th>\n");
        sb.append("        <th>Billing Cycle</th>\n");
        sb.append("        <th>Active Period</th>\n");
        sb.append("        <th style=\"text-align:right;\">Amount</th>\n");
        sb.append("      </tr>\n");
        sb.append("    </thead>\n");
        sb.append("    <tbody>\n");
        sb.append("      <tr>\n");
        sb.append("        <td><strong style=\"color:#0f172a;\">").append(planName).append("</strong> (Tier: ").append(tierId).append(")<br/><span style=\"color:#64748b;font-size:11px;\">Full CRM Access, Multi-User Team Quotas, Email & WhatsApp Automation</span></td>\n");
        sb.append("        <td>").append(billingCycle).append("</td>\n");
        sb.append("        <td>").append(periodText).append("</td>\n");
        sb.append("        <td style=\"text-align:right;font-weight:700;\">").append(formattedAmount).append("</td>\n");
        sb.append("      </tr>\n");
        sb.append("      <tr class=\"total-row\">\n");
        sb.append("        <td colspan=\"3\">Total Paid Amount</td>\n");
        sb.append("        <td style=\"text-align:right;\">").append(formattedAmount).append("</td>\n");
        sb.append("      </tr>\n");
        sb.append("    </tbody>\n");
        sb.append("  </table>\n");
        sb.append("  <div class=\"features-section\">\n");
        sb.append("    <div class=\"features-title\">🚀 Included Subscription Quotas & Features</div>\n");
        sb.append("    <div class=\"features-list\">\n");
        sb.append("      <div>✓ Staff Member Accounts: <strong>").append(staffLimit).append(" Members</strong></div>\n");
        sb.append("      <div>✓ Primary CRM Leads Quota: <strong>").append(leadLimit).append(" Leads</strong></div>\n");
        sb.append("      <div>✓ Email Marketing Quota: <strong>").append(emailLimit).append(" Emails/mo</strong></div>\n");
        if (hasWhatsapp) {
            sb.append("      <div>✓ Meta WhatsApp Cloud API: <strong>Active Broadcasts</strong></div>\n");
        }
        if (hasAi) {
            sb.append("      <div>✓ AI RAG Knowledge Base: <strong>Vector Search Enabled</strong></div>\n");
        }
        if (hasMultiCurrency) {
            sb.append("      <div>✓ Multi-Currency Billing: <strong>INR & USD Supported</strong></div>\n");
        }
        sb.append("    </div>\n");
        sb.append("  </div>\n");
        sb.append("  <div class=\"footer\">\n");
        sb.append("    <p>Thank you for subscribing to GyanVani AI Connect! For support or inquiries, contact <a href=\"mailto:support@gyanvaniai.online\" style=\"color:#6366f1;\">support@gyanvaniai.online</a>.</p>\n");
        sb.append("    <p style=\"font-size:10px;\">This is a computer-generated tax invoice. No signature required.</p>\n");
        sb.append("  </div>\n");
        sb.append("</div>\n");
        sb.append("</body>\n</html>");

        return sb.toString();
    }
}
