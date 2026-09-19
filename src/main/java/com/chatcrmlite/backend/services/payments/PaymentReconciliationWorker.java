package com.chatcrmlite.backend.services.payments;

import com.chatcrmlite.backend.dto.payments.NormalizedPaymentWebhookEvent;
import com.chatcrmlite.backend.dto.payments.PaymentStatusResult;
import com.chatcrmlite.backend.models.enums.PaymentTransactionStatus;
import com.chatcrmlite.backend.models.payments.PaymentAuditLog;
import com.chatcrmlite.backend.models.payments.PaymentTransaction;
import com.chatcrmlite.backend.repositories.payments.PaymentAuditLogRepository;
import com.chatcrmlite.backend.repositories.payments.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentReconciliationWorker {

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentProviderFactory providerFactory;
    private final PaymentWebhookProcessor webhookProcessor;
    private final PaymentAuditLogRepository auditLogRepository;

    @Scheduled(fixedDelayString = "${payment.reconciliation.interval-ms:600000}")
    @Transactional
    public void reconcilePendingPayments() {
        Instant cutoff = Instant.now().minus(15, ChronoUnit.MINUTES);
        List<PaymentTransaction> pendingTxList = transactionRepository.findUnresolvedTransactionsForReconciliation(
            List.of(PaymentTransactionStatus.INITIATED, PaymentTransactionStatus.PENDING, PaymentTransactionStatus.UNKNOWN),
            cutoff
        );

        if (pendingTxList.isEmpty()) {
            return;
        }

        log.info("Running payment reconciliation for {} unresolved transactions", pendingTxList.size());

        for (PaymentTransaction tx : pendingTxList) {
            try {
                reconcileSingleTransaction(tx);
            } catch (Exception e) {
                log.error("Failed to reconcile transaction {}: {}", tx.getId(), e.getMessage());
            }
        }
    }

    private void reconcileSingleTransaction(PaymentTransaction tx) {
        UUID tenantId = tx.getTenantId();
        PaymentProvider provider = providerFactory.getProvider(tx.getProvider());

        String lookupKey = tx.getProviderOrderId() != null ? tx.getProviderOrderId() : tx.getProviderPaymentId();
        if (lookupKey == null) {
            return;
        }

        PaymentStatusResult statusResult = provider.getPaymentStatus(tenantId, lookupKey);
        if (statusResult.getStatus() == PaymentTransactionStatus.SUCCESS) {
            log.info("Reconciliation found PAID status for transaction {}", tx.getId());

            NormalizedPaymentWebhookEvent normalizedEvent = NormalizedPaymentWebhookEvent.builder()
                .tenantId(tenantId)
                .integrationId(tx.getPaymentIntegration() != null ? tx.getPaymentIntegration().getId() : null)
                .provider(tx.getProvider())
                .providerEventKey("RECON_" + tx.getId() + "_" + Instant.now().getEpochSecond())
                .eventType("reconciliation_success")
                .orderReferenceId(tx.getOrder().getReferenceId())
                .providerPaymentId(statusResult.getProviderPaymentId() != null ? statusResult.getProviderPaymentId() : lookupKey)
                .providerOrderId(statusResult.getProviderOrderId())
                .amountMinor(tx.getAmountMinor())
                .currency(tx.getCurrency())
                .transactionStatus(PaymentTransactionStatus.SUCCESS)
                .occurredAt(statusResult.getPaidAt() != null ? statusResult.getPaidAt() : Instant.now())
                .build();

            webhookProcessor.processNormalizedEvent(normalizedEvent);

            PaymentAuditLog auditLog = PaymentAuditLog.builder()
                .orderId(tx.getOrder().getId())
                .transactionId(tx.getId())
                .actorType("RECONCILER")
                .actorId("PaymentReconciliationWorker")
                .action("RECONCILED")
                .oldStatus(tx.getStatus().name())
                .newStatus("SUCCESS")
                .metadata(Map.of("lookupKey", lookupKey))
                .build();
            auditLog.setTenantId(tenantId);
            auditLogRepository.save(auditLog);
        }
    }
}
