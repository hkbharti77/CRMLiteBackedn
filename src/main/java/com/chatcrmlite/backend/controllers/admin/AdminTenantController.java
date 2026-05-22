package com.chatcrmlite.backend.controllers.admin;

import com.chatcrmlite.backend.services.tenant.TenantResourceManager;
import com.chatcrmlite.backend.services.tenant.TenantResourceManager.ResourceType;
import com.chatcrmlite.backend.services.tenant.TenantTierService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API for cross-tenant management operations.
 *
 * SECURITY: @PreAuthorize("hasRole('ADMIN')") enforces that only users with
 * User.Role.ADMIN can reach any endpoint in this controller. This is enforced at the
 * Spring Security method-security level (via AOP proxy), not just at the URL matcher,
 * providing defence-in-depth beyond the URL-based restrictions in SecurityConfig.
 *
 * The role is granted at authentication time via AuthTokenFilter which loads the user's
 * Role from the database and converts it to a GrantedAuthority.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")  // class-level — applies to ALL endpoints in this controller
public class AdminTenantController {

    private final TenantResourceManager resourceManager;
    private final TenantTierService tierService;

    /**
     * Returns resource usage statistics for a given tenant.
     * Requires: ADMIN role.
     */
    @GetMapping("/{tenantId}/stats")
    public ResponseEntity<Map<String, Object>> getTenantStats(@PathVariable UUID tenantId) {
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
     * Requires: ADMIN role.
     * Note: In production this should write to a persistent override table
     * and emit an audit log entry. The current implementation is a placeholder.
     */
    @PostMapping("/{tenantId}/quotas/override")
    public ResponseEntity<String> overrideQuota(
            @PathVariable UUID tenantId,
            @RequestParam ResourceType type,
            @RequestParam long newLimit) {
        // TODO: Persist override to database and emit audit log
        return ResponseEntity.ok("Quota override applied successfully (Simulated)");
    }
}
