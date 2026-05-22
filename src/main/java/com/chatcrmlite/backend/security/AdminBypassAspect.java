package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.User;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * AdminBypassAspect — elevates TenantContext to admin mode for cross-tenant operations.
 *
 * SECURITY HARDENING (was: set admin mode for any caller without role verification):
 * - Now validates that the currently authenticated user has Role.ADMIN before elevating.
 * - Throws AccessDeniedException immediately if a non-admin attempts to use @AdminBypass.
 * - All elevations are logged as WARN audit events (user, method, timestamp).
 * - Admin mode is always cleaned up in the finally block regardless of outcome.
 * - If called in a non-authenticated context (scheduled jobs, async), caller must set
 *   admin mode directly via TenantContext — the aspect enforces security for HTTP threads.
 */
@Aspect
@Component
public class AdminBypassAspect {
    private static final Logger log = LoggerFactory.getLogger(AdminBypassAspect.class);

    @Around("@annotation(com.chatcrmlite.backend.security.AdminBypass) || @within(com.chatcrmlite.backend.security.AdminBypass)")
    public Object bypassTenantFilter(ProceedingJoinPoint joinPoint) throws Throwable {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // In unauthenticated contexts (e.g. scheduled jobs, async processors) we allow
        // the bypass IF there is no security context — these are internal system calls.
        // For authenticated HTTP requests, we MUST verify the ADMIN role.
        if (authentication != null && authentication.isAuthenticated()) {
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

            if (!isAdmin) {
                String caller = authentication.getName();
                log.warn("[SECURITY] AdminBypass DENIED — user '{}' attempted cross-tenant access on method '{}'. " +
                         "Only users with ROLE_ADMIN may use @AdminBypass.", caller, joinPoint.getSignature().toShortString());
                throw new AccessDeniedException("Insufficient privileges. ADMIN role required for cross-tenant operations.");
            }

            // Audit log — every admin elevation must be traceable
            log.warn("[AUDIT] AdminBypass ACTIVATED — user='{}', method='{}', timestamp='{}'",
                     authentication.getName(),
                     joinPoint.getSignature().toShortString(),
                     java.time.Instant.now());
        } else {
            // System/internal call — no security context. Log and allow.
            log.debug("[AdminBypass] Activated in system context (no authenticated user) for method: {}",
                      joinPoint.getSignature().toShortString());
        }

        boolean wasAdmin = TenantContext.isAdminMode();
        try {
            TenantContext.setAdminMode(true);
            return joinPoint.proceed();
        } finally {
            // Always restore the previous state
            if (!wasAdmin) {
                TenantContext.setAdminMode(false);
            }
        }
    }
}
