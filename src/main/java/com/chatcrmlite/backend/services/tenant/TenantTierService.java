package com.chatcrmlite.backend.services.tenant;

import com.chatcrmlite.backend.models.User.PlanType;
import com.chatcrmlite.backend.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantTierService {

    private final UserRepository userRepository;

    /**
     * Returns the plan type for a given owner user ID.
     *
     * Uses a direct scalar JPQL query (JOIN u.tenant) instead of findById() + getPlanType(),
     * which would trigger lazy initialization of User.tenant outside a transaction and cause
     * "Could not initialize proxy [Tenant#...] - no session" in async workers.
     * 
     * Handles Hibernate's inconsistent enum projection behavior (may return String or enum).
     * 
     * NOTE: Conversion from String to PlanType happens BEFORE caching to avoid ClassCastException
     * when retrieving cached values.
     */
    // @Cacheable(value = "tenant_tier", key = "#tenantId")  // DISABLED: Jackson deserializes enum as String
    public PlanType getTier(UUID tenantId) {
        log.info("🔍 [TenantTier] getTier called for tenant: {}", tenantId);
        String planTypeStr = userRepository.findPlanTypeByUserId(tenantId).orElse(null);
        
        if (planTypeStr == null) {
            log.warn("⚠️ [TenantTier] No plan type found for tenant {}. Defaulting to FREE", tenantId);
            return PlanType.FREE;
        }
        
        try {
            PlanType result = PlanType.valueOf(planTypeStr.toUpperCase());
            log.info("✅ [TenantTier] Resolved plan type {} for tenant {} (from DB string: '{}')", result, tenantId, planTypeStr);
            return result;
        } catch (IllegalArgumentException e) {
            log.error("❌ [TenantTier] Invalid PlanType string: '{}' for tenant {}. Defaulting to FREE", planTypeStr, tenantId, e);
            return PlanType.FREE;
        }
    }
}
