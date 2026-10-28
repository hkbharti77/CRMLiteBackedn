package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.exceptions.QuotaExceededException;
import com.chatcrmlite.backend.models.RagQuotaReservation;
import com.chatcrmlite.backend.models.TenantRagQuota;
import com.chatcrmlite.backend.repositories.RagQuotaReservationRepository;
import com.chatcrmlite.backend.repositories.TenantRagQuotaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantQuotaManager {

    private final TenantRagQuotaRepository tenantQuotaRepository;
    private final RagQuotaReservationRepository quotaReservationRepository;
    private final com.chatcrmlite.backend.services.tenant.EntitlementResolverService entitlementResolverService;

    public static int getPlanDailyPageLimit(String planId) {
        if (planId == null) return 5;
        return switch (planId.toUpperCase()) {
            case "FREE" -> 5;
            case "STARTER" -> 15;
            case "GROWTH", "PRO" -> 50;
            case "SCALE" -> 200;
            case "ENTERPRISE" -> 1000;
            default -> 5;
        };
    }

    public static int getPlanMonthlyChunkLimit(String planId) {
        if (planId == null) return 250;
        return switch (planId.toUpperCase()) {
            case "FREE" -> 250;
            case "STARTER" -> 1000;
            case "GROWTH", "PRO" -> 5000;
            case "SCALE" -> 20000;
            case "ENTERPRISE" -> 100000;
            default -> 250;
        };
    }

    public String resolveTenantPlanId(UUID tenantId) {
        if (tenantId == null || entitlementResolverService == null) {
            return "FREE";
        }
        try {
            var entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
            if (entitlements != null && entitlements.getBasePlanId() != null) {
                return entitlements.getBasePlanId().toUpperCase();
            }
        } catch (Exception e) {
            log.warn("Could not resolve plan for tenant {}, defaulting to FREE: {}", tenantId, e.getMessage());
        }
        return "FREE";
    }

    @Transactional
    public TenantRagQuota getOrCreateQuota(UUID tenantId) {
        String planId = resolveTenantPlanId(tenantId);
        int planDaily = getPlanDailyPageLimit(planId);
        int planMonthly = getPlanMonthlyChunkLimit(planId);

        try {
            tenantQuotaRepository.insertIfNotExists(tenantId, planDaily, planMonthly);
        } catch (Exception e) {
            log.debug("insertIfNotExists handled: {}", e.getMessage());
        }

        TenantRagQuota quota = tenantQuotaRepository.findById(tenantId)
                .orElseGet(() -> {
                    TenantRagQuota defaultQuota = TenantRagQuota.builder()
                            .tenantId(tenantId)
                            .dailyPageLimit(planDaily)
                            .dailyPagesConsumed(0)
                            .monthlyChunkLimit(planMonthly)
                            .monthlyChunksConsumed(0)
                            .timezone("UTC")
                            .lastDailyResetAt(Instant.now())
                            .lastMonthlyResetAt(Instant.now())
                            .build();
                    return tenantQuotaRepository.save(defaultQuota);
                });

        if (quota.getDailyPageLimit() == 50 && quota.getMonthlyChunkLimit() == 2500 && !"GROWTH".equalsIgnoreCase(planId) && !"PRO".equalsIgnoreCase(planId)) {
            quota.setDailyPageLimit(planDaily);
            quota.setMonthlyChunkLimit(planMonthly);
            quota = tenantQuotaRepository.save(quota);
        }

        applyTimezoneAwareResets(quota);
        return quota;
    }

    /**
     * Atomically reserves quota for a crawl or ingestion job.
     * Always acquires a pessimistic lock on the tenant quota row first to prevent AB-BA deadlocks.
     */
    @Transactional
    public RagQuotaReservation reserveQuota(UUID tenantId, UUID jobId, int requestedPages, int estimatedChunks) {
        if (requestedPages < 0 || estimatedChunks < 0) {
            throw new IllegalArgumentException("Requested pages and chunks must be non-negative");
        }

        // Check if an existing reservation already exists for this job (idempotency check)
        Optional<RagQuotaReservation> existing = quotaReservationRepository.findByJobId(jobId);
        if (existing.isPresent()) {
            RagQuotaReservation res = existing.get();
            if ("PENDING".equals(res.getStatus())) {
                log.info("Quota reservation already exists for job {}: pages={}, chunks={}", 
                        jobId, res.getPagesReserved(), res.getChunksReserved());
                return res;
            }
        }

        String planId = resolveTenantPlanId(tenantId);
        int planDaily = getPlanDailyPageLimit(planId);
        int planMonthly = getPlanMonthlyChunkLimit(planId);

        try {
            tenantQuotaRepository.insertIfNotExists(tenantId, planDaily, planMonthly);
        } catch (Exception e) {
            log.debug("insertIfNotExists handled in reserveQuota: {}", e.getMessage());
        }

        // Uniform locking order: ALWAYS lock tenant_rag_quotas first
        TenantRagQuota quota = tenantQuotaRepository.findByTenantIdForUpdate(tenantId)
                .map(q -> {
                    if (q.getDailyPageLimit() == 50 && q.getMonthlyChunkLimit() == 2500 && !"GROWTH".equalsIgnoreCase(planId) && !"PRO".equalsIgnoreCase(planId)) {
                        q.setDailyPageLimit(planDaily);
                        q.setMonthlyChunkLimit(planMonthly);
                        return tenantQuotaRepository.save(q);
                    }
                    return q;
                })
                .orElseGet(() -> {
                    log.info("Initializing default RAG quota for tenant {} on plan {}", tenantId, planId);
                    TenantRagQuota defaultQuota = TenantRagQuota.builder()
                            .tenantId(tenantId)
                            .dailyPageLimit(planDaily)
                            .dailyPagesConsumed(0)
                            .monthlyChunkLimit(planMonthly)
                            .monthlyChunksConsumed(0)
                            .timezone("UTC")
                            .lastDailyResetAt(Instant.now())
                            .lastMonthlyResetAt(Instant.now())
                            .build();
                    return tenantQuotaRepository.save(defaultQuota);
                });

        applyTimezoneAwareResets(quota);

        Instant now = Instant.now();
        int pendingPages = quotaReservationRepository.sumPendingPagesByTenantId(tenantId, now);
        int pendingChunks = quotaReservationRepository.sumPendingChunksByTenantId(tenantId, now);

        if (requestedPages > 0) {
            int totalProjectedPages = quota.getDailyPagesConsumed() + pendingPages + requestedPages;
            if (totalProjectedPages > quota.getDailyPageLimit()) {
                int available = Math.max(0, quota.getDailyPageLimit() - (quota.getDailyPagesConsumed() + pendingPages));
                log.warn("Daily page quota exceeded for tenant {}: limit={}, requested={}, available={}",
                        tenantId, quota.getDailyPageLimit(), requestedPages, available);
                throw new QuotaExceededException("DAILY_PAGES", quota.getDailyPageLimit(), requestedPages, available,
                        "Daily page quota limit (" + quota.getDailyPageLimit() + ") exceeded. Available: " + available);
            }
        }

        if (estimatedChunks > 0) {
            int totalProjectedChunks = quota.getMonthlyChunksConsumed() + pendingChunks + estimatedChunks;
            if (totalProjectedChunks > quota.getMonthlyChunkLimit()) {
                int available = Math.max(0, quota.getMonthlyChunkLimit() - (quota.getMonthlyChunksConsumed() + pendingChunks));
                log.warn("Monthly chunk quota exceeded for tenant {}: limit={}, requested={}, available={}",
                        tenantId, quota.getMonthlyChunkLimit(), estimatedChunks, available);
                throw new QuotaExceededException("MONTHLY_CHUNKS", quota.getMonthlyChunkLimit(), estimatedChunks, available,
                        "Monthly chunk quota limit (" + quota.getMonthlyChunkLimit() + ") exceeded. Available: " + available);
            }
        }

        RagQuotaReservation reservation = RagQuotaReservation.builder()
                .tenantId(tenantId)
                .jobId(jobId)
                .pagesReserved(requestedPages)
                .pagesConsumed(0)
                .chunksReserved(estimatedChunks)
                .chunksConsumed(0)
                .status("PENDING")
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .updatedAt(now)
                .build();

        return quotaReservationRepository.save(reservation);
    }

    /**
     * Atomically commits actual usage and converts the reservation into consumed counters.
     * Strictly idempotent: if already committed, returns without duplicate charges.
     * Always acquires pessimistic lock on tenant quota first.
     */
    @Transactional
    public void settleReservation(UUID tenantId, UUID jobId, int actualPages, int actualChunks) {
        // Uniform locking order: ALWAYS lock tenant_rag_quotas first
        tenantQuotaRepository.findByTenantIdForUpdate(tenantId);

        Optional<RagQuotaReservation> resOpt = quotaReservationRepository.findPendingByJobIdForUpdate(jobId);
        if (resOpt.isEmpty()) {
            log.info("Reservation for job {} not in PENDING state during settlement (may be already committed or released)", jobId);
            return;
        }

        Instant now = Instant.now();
        int rowsUpdated = quotaReservationRepository.commitPendingReservation(jobId, actualPages, actualChunks, now);
        if (rowsUpdated > 0) {
            tenantQuotaRepository.incrementConsumedCounters(tenantId, actualPages, actualChunks);
            log.info("Successfully settled quota reservation for job {}: actualPages={}, actualChunks={}", 
                    jobId, actualPages, actualChunks);
        }
    }

    /**
     * Releases a pending reservation when a job fails or is cancelled without consuming quota.
     * Uniform locking order: locks tenant quota first.
     */
    @Transactional
    public void releaseReservation(UUID tenantId, UUID jobId) {
        // Uniform locking order: ALWAYS lock tenant_rag_quotas first
        tenantQuotaRepository.findByTenantIdForUpdate(tenantId);

        int rowsUpdated = quotaReservationRepository.releasePendingReservation(jobId, Instant.now());
        if (rowsUpdated > 0) {
            log.info("Released pending quota reservation for job {}", jobId);
        }
    }

    /**
     * Heartbeat renewal for long-running crawl jobs (> 15 minutes).
     */
    @Transactional
    public boolean renewReservation(UUID jobId, Duration extension) {
        Instant newExpiry = Instant.now().plus(extension);
        int rowsUpdated = quotaReservationRepository.renewPendingReservation(jobId, newExpiry, Instant.now());
        return rowsUpdated > 0;
    }

    /**
     * Background cleaner to expire reservations that exceeded their lease time.
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public int cleanupExpiredReservations() {
        int count = quotaReservationRepository.expireStaleReservations(Instant.now());
        if (count > 0) {
            log.warn("Expired {} stale pending quota reservations", count);
        }
        return count;
    }

    private void applyTimezoneAwareResets(TenantRagQuota quota) {
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(quota.getTimezone());
        } catch (Exception e) {
            zoneId = ZoneOffset.UTC;
        }

        ZonedDateTime nowZoned = Instant.now().atZone(zoneId);
        ZonedDateTime lastDailyZoned = quota.getLastDailyResetAt() != null 
                ? quota.getLastDailyResetAt().atZone(zoneId) 
                : nowZoned;

        if (nowZoned.toLocalDate().isAfter(lastDailyZoned.toLocalDate())) {
            log.info("Performing daily RAG quota reset for tenant {} in timezone {}", quota.getTenantId(), zoneId);
            quota.setDailyPagesConsumed(0);
            quota.setLastDailyResetAt(Instant.now());
            tenantQuotaRepository.resetDailyPages(quota.getTenantId(), Instant.now());
        }

        ZonedDateTime lastMonthlyZoned = quota.getLastMonthlyResetAt() != null 
                ? quota.getLastMonthlyResetAt().atZone(zoneId) 
                : nowZoned;

        if (nowZoned.getYear() > lastMonthlyZoned.getYear() ||
                (nowZoned.getYear() == lastMonthlyZoned.getYear() && nowZoned.getMonthValue() > lastMonthlyZoned.getMonthValue())) {
            log.info("Performing monthly RAG quota reset for tenant {} in timezone {}", quota.getTenantId(), zoneId);
            quota.setMonthlyChunksConsumed(0);
            quota.setLastMonthlyResetAt(Instant.now());
            tenantQuotaRepository.resetMonthlyChunks(quota.getTenantId(), Instant.now());
        }
    }
}
