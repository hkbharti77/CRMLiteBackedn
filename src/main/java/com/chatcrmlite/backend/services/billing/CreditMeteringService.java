package com.chatcrmlite.backend.services.billing;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantCreditTransaction;
import com.chatcrmlite.backend.repositories.TenantCreditTransactionRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditMeteringService {

    private final TenantRepository tenantRepository;
    private final TenantCreditTransactionRepository creditTransactionRepository;

    public static class InsufficientCreditsException extends RuntimeException {
        public InsufficientCreditsException(String message) {
            super(message);
        }
    }

    /**
     * Checks if tenant has at least required credits.
     */
    @Transactional(readOnly = true)
    public boolean hasAvailableCredits(UUID tenantId, long requiredCredits) {
        if (tenantId == null) return false;
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return false;
        long current = tenant.getAiCreditBalance() != null ? tenant.getAiCreditBalance() : 0L;
        return current >= requiredCredits;
    }

    /**
     * Atomically deducts AI credits from tenant balance and writes an immutable ledger entry.
     */
    @Transactional
    public synchronized void deductCredits(UUID tenantId, long amount, String category, String description, String referenceId) {
        if (tenantId == null || amount <= 0) return;

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        long currentBalance = tenant.getAiCreditBalance() != null ? tenant.getAiCreditBalance() : 0L;
        if (currentBalance < amount) {
            log.warn("⚠️ Tenant {} has insufficient AI credits. Available: {}, Requested: {}", tenantId, currentBalance, amount);
            throw new InsufficientCreditsException("Insufficient AI credits remaining (" + currentBalance + "). Please top up your credit pack or upgrade your plan.");
        }

        long newBalance = currentBalance - amount;
        tenant.setAiCreditBalance(newBalance);
        tenantRepository.save(tenant);

        TenantCreditTransaction tx = TenantCreditTransaction.builder()
                .tenant(tenant)
                .amount(-amount)
                .balanceAfter(newBalance)
                .category(category != null ? category : "USAGE_DEDUCTION")
                .description(description != null ? description : "AI inference / RAG query")
                .referenceId(referenceId)
                .build();
        creditTransactionRepository.save(tx);

        log.debug("⚡ Deducted {} credits for tenant {}. Balance now: {}", amount, tenantId, newBalance);
    }

    /**
     * Adds AI credits to tenant balance (e.g. plan monthly grant, top-up purchase) and writes a ledger entry.
     */
    @Transactional
    public synchronized void addCredits(UUID tenantId, long amount, String category, String description, String referenceId) {
        if (tenantId == null || amount <= 0) return;

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        long currentBalance = tenant.getAiCreditBalance() != null ? tenant.getAiCreditBalance() : 0L;
        long newBalance = currentBalance + amount;
        tenant.setAiCreditBalance(newBalance);
        tenantRepository.save(tenant);

        TenantCreditTransaction tx = TenantCreditTransaction.builder()
                .tenant(tenant)
                .amount(amount)
                .balanceAfter(newBalance)
                .category(category != null ? category : "TOP_UP_PACK")
                .description(description != null ? description : "AI Credit top-up")
                .referenceId(referenceId)
                .build();
        creditTransactionRepository.save(tx);

        log.info("💰 Added {} credits to tenant {}. New balance: {}", amount, tenantId, newBalance);
    }

    @Transactional(readOnly = true)
    public Page<TenantCreditTransaction> getTransactionHistory(UUID tenantId, Pageable pageable) {
        return creditTransactionRepository.findByTenantId(tenantId, pageable);
    }
}
