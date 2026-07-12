package com.chatcrmlite.backend.services.tenant;

import com.chatcrmlite.backend.models.User.PlanType;
import com.chatcrmlite.backend.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import com.chatcrmlite.backend.event.TenantSubscriptionUpdatedEvent;

import java.util.UUID;@Service
@Slf4j
public class TenantTierService {

    private final com.chatcrmlite.backend.repositories.TenantSubscriptionRepository tenantSubscriptionRepository;
    private final com.github.benmanes.caffeine.cache.Cache<UUID, PlanType> tierCache;

    @org.springframework.beans.factory.annotation.Value("${app.performance.threshold.cache-ms:10}")
    private long cacheThresholdMs;

    public TenantTierService(com.chatcrmlite.backend.repositories.TenantSubscriptionRepository tenantSubscriptionRepository,
                             io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.tenantSubscriptionRepository = tenantSubscriptionRepository;
        this.tierCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(java.time.Duration.ofMinutes(10))
                .recordStats()
                .build();
        io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics.monitor(meterRegistry, tierCache, "tenant_tier_cache");
    }

    public PlanType getTier(UUID tenantId) {
        long start = System.currentTimeMillis();
        PlanType plan = tierCache.getIfPresent(tenantId);
        long duration = System.currentTimeMillis() - start;
        
        if (duration > cacheThresholdMs) {
            log.warn("SLOW_OPERATION: Cache lookup exceeded threshold traceId={} durationMs={} thresholdMs={}", 
                     org.slf4j.MDC.get("traceId"), duration, cacheThresholdMs);
        }

        if (plan != null) {
            log.debug("Cache hit tenantId={} traceId={} processingTimeMs={}", tenantId, org.slf4j.MDC.get("traceId"), duration);
            return plan;
        }
        log.debug("Cache miss tenantId={} traceId={}", tenantId, org.slf4j.MDC.get("traceId"));
        return tierCache.get(tenantId, id -> fetchTierFromDb(id, start));
    }

    /**
     * Invalidates the cache for a specific tenant ID.
     * Call this when a tenant's subscription plan changes.
     */
    public void invalidateCache(UUID tenantId) {
        log.info("Invalidating cache tenantId={} traceId={}", tenantId, org.slf4j.MDC.get("traceId"));
        tierCache.invalidate(tenantId);
    }

    /**
     * Listens for subscription update events and invalidates the cache
     * only after the transaction has successfully committed.
     * Avoids invalidating cache on rolled-back transactions.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleSubscriptionUpdated(TenantSubscriptionUpdatedEvent event) {
        log.info("Handling subscription updated event after commit for tenantId={} traceId={}", 
                 event.getTenantId(), org.slf4j.MDC.get("traceId"));
        invalidateCache(event.getTenantId());
    }

    private PlanType fetchTierFromDb(UUID tenantId, long startTime) {
        com.chatcrmlite.backend.models.TenantSubscription sub = 
                tenantSubscriptionRepository.findByTenantId(tenantId).orElse(null);
                
        String planTypeStr = (sub != null && sub.getPlan() != null) 
                ? sub.getPlan().getId() 
                : "FREE";
        
        try {
            PlanType result = PlanType.valueOf(planTypeStr.toUpperCase());
            log.info("Resolved plan type tenantId={} traceId={} planType={} processingTimeMs={}", 
                     tenantId, org.slf4j.MDC.get("traceId"), result, System.currentTimeMillis() - startTime);
            return result;
        } catch (IllegalArgumentException e) {
            log.error("Invalid plan type tenantId={} traceId={} errorType={} processingTimeMs={}", 
                      tenantId, org.slf4j.MDC.get("traceId"), e.getClass().getSimpleName(), System.currentTimeMillis() - startTime, e);
            return PlanType.FREE;
        }
    }
}
