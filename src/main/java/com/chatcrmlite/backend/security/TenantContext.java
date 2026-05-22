package com.chatcrmlite.backend.security;

import java.util.UUID;

/**
 * Holder for the current tenant's context.
 * Uses ThreadLocal to ensure tenant isolation within a single request.
 */
public class TenantContext {
    private static final ThreadLocal<UUID> currentTenant = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> adminMode = ThreadLocal.withInitial(() -> false);

    public static void setTenantId(UUID tenantId) {
        currentTenant.set(tenantId);
    }

    public static UUID getTenantId() {
        return currentTenant.get();
    }

    public static void setAdminMode(boolean enabled) {
        adminMode.set(enabled);
    }

    public static boolean isAdminMode() {
        return adminMode.get();
    }

    public static void clear() {
        currentTenant.remove();
        adminMode.remove();
    }
}
