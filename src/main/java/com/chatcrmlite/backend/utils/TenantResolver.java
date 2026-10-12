package com.chatcrmlite.backend.utils;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenantResolver {

    private final UserRepository userRepository;

    /**
     * Resolves the active Tenant / Business ID for multi-tenant isolation.
     * Hierarchy (Strict Security Enforcement):
     * 1. If user is authenticated in Spring Security context:
     *    a. If user is SUPER_ADMIN or AdminMode is active, allow targeted tenant from header/param if provided.
     *    b. Otherwise, the authenticated user's tenant is STRICTLY AUTHORITATIVE.
     *       Any client-provided X-Tenant-ID or query businessId that does not match is blocked and logged.
     * 2. If TenantContext has a pre-resolved tenant ID, use it.
     * 3. Fallback for unauthenticated endpoints (e.g. public callbacks) to provided parameter or "default".
     */
    public String resolveBusinessId(String xTenantIdHeader, String queryBusinessId) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String email && !email.isBlank()) {
                Optional<User> userOpt = userRepository.findByEmail(email);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    boolean isPlatformAdmin = user.getRole() == User.Role.SUPER_ADMIN || TenantContext.isAdminMode();

                    // Platform super-admins can target a specific tenant during administration
                    if (isPlatformAdmin) {
                        if (isValidIdentifier(xTenantIdHeader)) {
                            return xTenantIdHeader.trim();
                        }
                        if (isValidIdentifier(queryBusinessId)) {
                            return queryBusinessId.trim();
                        }
                    }

                    // For standard authenticated users, their assigned tenant is non-overridable
                    if (user.getTenant() != null && user.getTenant().getId() != null) {
                        String realTenantId = user.getTenant().getId().toString();
                        
                        // Detect and log spoofing attempts
                        checkAndLogSpoofingAttempt(user.getEmail(), realTenantId, xTenantIdHeader, "X-Tenant-ID Header");
                        checkAndLogSpoofingAttempt(user.getEmail(), realTenantId, queryBusinessId, "businessId Query Param");

                        return realTenantId;
                    }

                    if (user.getId() != null) {
                        return user.getId().toString();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Authenticated tenant resolution exception: {}", e.getMessage());
        }

        // Check if pre-set in TenantContext
        UUID contextTenant = TenantContext.getTenantId();
        if (contextTenant != null) {
            return contextTenant.toString();
        }

        // Unauthenticated fallback
        if (isValidIdentifier(xTenantIdHeader)) {
            return xTenantIdHeader.trim();
        }
        if (isValidIdentifier(queryBusinessId)) {
            return queryBusinessId.trim();
        }

        return "default";
    }

    /**
     * Resolves the active Tenant UUID for the current context.
     */
    public UUID resolveTenantId(String xTenantIdHeader, String queryBusinessId) {
        String resolved = resolveBusinessId(xTenantIdHeader, queryBusinessId);
        try {
            return UUID.fromString(resolved);
        } catch (IllegalArgumentException e) {
            UUID contextTenant = TenantContext.getTenantId();
            if (contextTenant != null) {
                return contextTenant;
            }
            return null;
        }
    }

    /**
     * Resolves the verified tenant UUID of the currently authenticated user.
     * Throws 401 if unauthenticated, or 403 if user has no assigned tenant.
     */
    public UUID resolveAuthenticatedTenantId() {
        UUID contextTenant = TenantContext.getTenantId();
        if (contextTenant != null) {
            return contextTenant;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof String email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        if (user.getTenant() == null || user.getTenant().getId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User does not belong to an active organization");
        }

        return user.getTenant().getId();
    }

    private boolean isValidIdentifier(String id) {
        return id != null && !id.isBlank() && !"default".equalsIgnoreCase(id.trim());
    }

    private void checkAndLogSpoofingAttempt(String userEmail, String realTenantId, String attemptedId, String source) {
        if (isValidIdentifier(attemptedId) && !attemptedId.trim().equalsIgnoreCase(realTenantId)) {
            log.warn("🚨 [SECURITY ALERT] Prevented tenant spoofing: User '{}' passed {}='{}' but authentic tenant is '{}'",
                    userEmail, source, attemptedId.trim(), realTenantId);
        }
    }
}
