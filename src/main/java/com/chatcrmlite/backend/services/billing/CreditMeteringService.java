package com.chatcrmlite.backend.services.billing;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantCreditReservation;
import com.chatcrmlite.backend.models.TenantCreditReservation.ReservationStatus;
import com.chatcrmlite.backend.models.TenantCreditTransaction;
import com.chatcrmlite.backend.repositories.TenantCreditReservationRepository;
import com.chatcrmlite.backend.repositories.TenantCreditTransactionRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditMeteringService {

    private final TenantRepository tenantRepository;
    private final TenantCreditTransactionRepository creditTransactionRepository;
    private final TenantCreditReservationRepository creditReservationRepository;

    public static class InsufficientCreditsException extends RuntimeException {
        public InsufficientCreditsException(String message) {
            super(message);
        }
    }

    /**
     * Checks if tenant has at least required credits after accounting for active pending reservations.
     */
    @Transactional(readOnly = true)
    public boolean hasAvailableCredits(UUID tenantId, long requiredCredits) {
        if (tenantId == null || requiredCredits <= 0) return false;
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return false;
        long totalBalance = tenant.getAiCreditBalance() != null ? tenant.getAiCreditBalance() : 0L;
        long activeReserved = creditReservationRepository.sumActiveReservedCredits(tenantId, LocalDateTime.now());
        long available = totalBalance - activeReserved;
        return available >= requiredCredits;
    }

    /**
     * Phase 1: Atomically reserves AI credits before starting generation or stream.
     * Uses pessimistic write lock on Tenant to ensure zero overspending.
     */
    @Transactional
    public UUID reserveCredits(UUID tenantId, long amount, Duration ttl, String referenceId) {
        if (tenantId == null || amount <= 0) return null;

        Tenant tenant = tenantRepository.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        long totalBalance = tenant.getAiCreditBalance() != null ? tenant.getAiCreditBalance() : 0L;
        long activeReserved = creditReservationRepository.sumActiveReservedCredits(tenantId, LocalDateTime.now());
        long available = totalBalance - activeReserved;

        if (available < amount) {
            log.warn("⚠️ Tenant {} has insufficient unreserved credits. Total: {}, Reserved: {}, Available: {}, Requested: {}",
                    tenantId, totalBalance, activeReserved, available, amount);
            throw new InsufficientCreditsException("Insufficient AI credits available (Available: " + available + "). Please top up your credit pack.");
        }

        Duration safeTtl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : Duration.ofMinutes(5);
        TenantCreditReservation reservation = TenantCreditReservation.builder()
                .tenant(tenant)
                .amount(amount)
                .status(ReservationStatus.PENDING)
                .referenceId(referenceId)
                .expiresAt(LocalDateTime.now().plus(safeTtl))
                .build();

        reservation = creditReservationRepository.save(reservation);
        log.debug("🔒 Reserved {} credits for tenant {} (reservationId={}, expiresAt={})",
                amount, tenantId, reservation.getId(), reservation.getExpiresAt());
        return reservation.getId();
    }

    /**
     * Phase 2a: Settles the reservation upon successful response completion.
     * Decrements the permanent tenant balance, updates reservation to SETTLED, and writes ledger entry.
     */
    @Transactional
    public void settleReservation(UUID reservationId, long actualAmount, String description) {
        if (reservationId == null) return;

        TenantCreditReservation reservation = creditReservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            log.warn("⚠️ Cannot settle credit reservation {} — not found", reservationId);
            return;
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            log.debug("ℹ️ Credit reservation {} already finalized with status {}", reservationId, reservation.getStatus());
            return;
        }

        Tenant tenant = tenantRepository.findByIdForUpdate(reservation.getTenant().getId())
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + reservation.getTenant().getId()));

        long settleAmount = actualAmount > 0 ? actualAmount : reservation.getAmount();
        long currentBalance = tenant.getAiCreditBalance() != null ? tenant.getAiCreditBalance() : 0L;
        long newBalance = Math.max(0, currentBalance - settleAmount);
        tenant.setAiCreditBalance(newBalance);
        tenantRepository.save(tenant);

        reservation.setStatus(ReservationStatus.SETTLED);
        reservation.setSettledAt(LocalDateTime.now());
        creditReservationRepository.save(reservation);

        TenantCreditTransaction tx = TenantCreditTransaction.builder()
                .tenant(tenant)
                .amount(-settleAmount)
                .balanceAfter(newBalance)
                .category("USAGE_DEDUCTION")
                .description(description != null ? description : "AI Streaming Query Completed")
                .referenceId(reservation.getReferenceId())
                .build();
        creditTransactionRepository.save(tx);

        log.debug("✅ Settled credit reservation {} ({} credits). Balance now: {}",
                reservationId, settleAmount, newBalance);
    }

    /**
     * Phase 2b: Immediately releases the reservation back to the available pool.
     * Called on client disconnect, model failure, or timeout.
     */
    @Transactional
    public void releaseReservation(UUID reservationId) {
        if (reservationId == null) return;

        TenantCreditReservation reservation = creditReservationRepository.findById(reservationId).orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.PENDING) {
            return;
        }

        reservation.setStatus(ReservationStatus.RELEASED);
        reservation.setSettledAt(LocalDateTime.now());
        creditReservationRepository.save(reservation);
        log.info("🔓 Released credit reservation {} ({} credits)", reservationId, reservation.getAmount());
    }

    /**
     * Reconciles expired/stale reservations (> 5m TTL).
     */
    @Transactional
    public int reconcileStaleReservations() {
        List<TenantCreditReservation> staleList = creditReservationRepository.findStaleReservations(LocalDateTime.now());
        if (staleList.isEmpty()) {
            return 0;
        }

        for (TenantCreditReservation r : staleList) {
            r.setStatus(ReservationStatus.EXPIRED);
            r.setSettledAt(LocalDateTime.now());
            creditReservationRepository.save(r);
            UUID tId = r.getTenant() != null ? r.getTenant().getId() : null;
            log.info("♻️ Reconciled expired reservation {} (tenantId={}, amount={})",
                    r.getId(), tId, r.getAmount());
        }
        return staleList.size();
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
