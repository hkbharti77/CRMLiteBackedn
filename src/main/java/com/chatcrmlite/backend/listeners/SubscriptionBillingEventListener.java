package com.chatcrmlite.backend.listeners;

import com.chatcrmlite.backend.event.PlanPaymentSuccessEvent;
import com.chatcrmlite.backend.models.BillingTransaction;
import com.chatcrmlite.backend.models.BillingTransaction.InvoiceEmailStatus;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BillingTransactionRepository;
import com.chatcrmlite.backend.repositories.TenantSubscriptionRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionBillingEventListener {

    private final BillingTransactionRepository billingTransactionRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePlanPaymentSuccess(PlanPaymentSuccessEvent event) {
        log.info("📩 [AFTER_COMMIT] Processing PlanPaymentSuccessEvent for tenant: {}, tx: {}", 
                event.getTenantId(), event.getTransactionId());

        if (event.getTransactionId() == null) {
            log.warn("⚠️ Cannot send invoice email — null transaction ID in event.");
            return;
        }

        BillingTransaction transaction = billingTransactionRepository.findById(event.getTransactionId())
                .orElse(null);

        if (transaction == null) {
            log.warn("⚠️ Billing transaction not found for ID: {}", event.getTransactionId());
            return;
        }

        // Email Idempotency Check
        if (transaction.getInvoiceEmailStatus() == InvoiceEmailStatus.SENT) {
            log.info("ℹ️ Invoice email already SENT for transaction: {}. Skipping duplicate dispatch.", transaction.getId());
            return;
        }

        TenantSubscription sub = tenantSubscriptionRepository.findByTenantId(event.getTenantId())
                .orElse(null);

        if (sub == null || sub.getPlan() == null) {
            log.warn("⚠️ Subscription or plan missing for tenant: {}", event.getTenantId());
            return;
        }

        String toEmail = event.getUserEmail();
        String recipientName = "Valued Customer";

        if (toEmail == null || toEmail.isBlank()) {
            User owner = userRepository.findFirstByTenantIdAndRole(event.getTenantId(), User.Role.OWNER)
                    .orElse(null);
            if (owner != null) {
                toEmail = owner.getEmail();
                recipientName = owner.getDisplayName() != null ? owner.getDisplayName() : "Workspace Owner";
            }
        }

        if (toEmail == null || toEmail.isBlank()) {
            log.warn("⚠️ No valid recipient email found for tenant: {}. Aborting invoice email.", event.getTenantId());
            return;
        }

        try {
            log.info("📧 Dispatching plan purchase invoice email to {} for plan: {}", toEmail, sub.getPlan().getName());
            
            emailService.sendPlanPurchaseInvoiceEmail(
                    toEmail,
                    recipientName,
                    sub.getPlan().getName(),
                    sub.getPlan().getId(),
                    sub.getBillingCycle() != null ? sub.getBillingCycle().name() : "MONTHLY",
                    transaction.getAmount(),
                    transaction.getCurrency(),
                    transaction.getGatewayTransactionId(),
                    transaction.getPaymentGateway() != null ? transaction.getPaymentGateway().name() : "RAZORPAY",
                    sub.getCurrentPeriodStart(),
                    sub.getCurrentPeriodEnd(),
                    sub.getPlan()
            );

            transaction.setInvoiceEmailStatus(InvoiceEmailStatus.SENT);
            transaction.setInvoiceEmailSentAt(LocalDateTime.now());
            billingTransactionRepository.save(transaction);
            
            log.info("✅ Invoice email marked SENT for transaction: {}", transaction.getId());
        } catch (Exception e) {
            log.error("❌ Failed to send invoice email for transaction: {}: {}", transaction.getId(), e.getMessage(), e);
            transaction.setInvoiceEmailStatus(InvoiceEmailStatus.FAILED);
            billingTransactionRepository.save(transaction);
        }
    }
}
