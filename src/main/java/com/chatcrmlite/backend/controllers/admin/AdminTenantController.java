package com.chatcrmlite.backend.controllers.admin;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.tenant.TenantResourceManager;
import com.chatcrmlite.backend.services.tenant.TenantResourceManager.ResourceType;
import com.chatcrmlite.backend.services.tenant.TenantTierService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API for tenant resource management operations.
 *
 * SECURITY:
 * - Platform Admins / Super Admins can access any tenant's metrics.
 * - Tenant Admins / Owners can only access their own tenant's metrics.
 * - Cross-tenant queries by tenant-level administrators are blocked with 403 Forbidden.
 * - Quota overrides are strictly restricted to platform administrators.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'PLATFORM_ADMIN', 'SUPER_ADMIN')")
public class AdminTenantController {

    private final TenantResourceManager resourceManager;
    private final TenantTierService tierService;
    private final UserRepository userRepository;

    /**
     * Returns resource usage statistics for a given tenant.
     * Platform Admins/Super Admins: Can view any tenant's stats.
     * Tenant Admins/Owners: Restricted strictly to own tenant stats.
     */
    @GetMapping("/{tenantId}/stats")
    public ResponseEntity<Map<String, Object>> getTenantStats(
            @PathVariable UUID tenantId,
            @AuthenticationPrincipal String email) {

        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User caller = userRepository.findByEmail(email).orElse(null);
        if (caller == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        boolean isPlatformAdmin = caller.getRole() == User.Role.SUPER_ADMIN;
        if (!isPlatformAdmin) {
            if (caller.getTenant() == null || !tenantId.equals(caller.getTenant().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("tenantId", tenantId);
        stats.put("tier", tierService.getTier(tenantId));

        Map<String, Object> resourceStats = new HashMap<>();
        for (ResourceType type : ResourceType.values()) {
            resourceStats.put(type.name(), resourceManager.getStatus(tenantId, type));
        }
        stats.put("resources", resourceStats);

        return ResponseEntity.ok(stats);
    }

    /**
     * Overrides the resource quota for a tenant.
     * Requires: PLATFORM_ADMIN or SUPER_ADMIN role.
     */
    @PostMapping("/{tenantId}/quotas/override")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<String> overrideQuota(
            @PathVariable UUID tenantId,
            @RequestParam ResourceType type,
            @RequestParam long newLimit) {
        return ResponseEntity.ok("Quota override applied successfully (Simulated)");
    }
}
