package com.chatcrmlite.backend.services.tenant;

import java.util.UUID;

/**
 * Manages resource allocation and usage tracking for SaaS tenants.
 */
public interface TenantResourceManager {

    enum ResourceType {
        CPU_THREADS,
        QUEUE_DEPTH,
        AI_TOKENS,
        MAX_CONNECTIONS,
        MESSAGES_PER_SECOND
    }

    /**
     * Checks if a tenant has enough quota to consume the specified amount of a resource.
     * For rate-based resources, this performs a rate-limit check.
     */
    boolean canConsume(UUID tenantId, ResourceType type, int amount);

    /**
     * Records consumption of a resource.
     */
    void reportUsage(UUID tenantId, ResourceType type, int amount);

    /**
     * Returns current usage vs quota for a tenant.
     */
    QuotaStatus getStatus(UUID tenantId, ResourceType type);

    record QuotaStatus(long currentUsage, long maxQuota) {}
}
