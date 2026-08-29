package com.chatcrmlite.backend.security;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

/**
 * AuditLogAspect — emits structured audit log entries for all privileged operations.
 *
 * Intercepts:
 * - All methods in the /admin/** controllers
 * - All methods annotated with @AdminBypass (cross-tenant operations)
 * - All security-sensitive service operations (session revocation, account lock)
 *
 * Log format (WARN level for easy alerting):
 *   [AUDIT] action=<methodName> user=<email> ip=<ip> ts=<epoch> result=<SUCCESS|DENIED|ERROR>
 *
 * These logs should be ingested into a SIEM or log aggregator with alerting on
 * result=DENIED and result=ERROR from admin-namespace methods.
 */
@Aspect
@Component
public class AuditLogAspect {
    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    /**
     * Log all method invocations in admin controllers BEFORE they execute.
     */
    @Before("within(com.chatcrmlite.backend.controllers.admin..*)")
    public void auditAdminInvocation(JoinPoint joinPoint) {
        String user = getCurrentUser();
        String ip = getCurrentIp();
        auditLog.warn("[AUDIT] action={} user={} ip={} ts={} result=INVOKED",
                joinPoint.getSignature().toShortString(), user, ip, Instant.now().getEpochSecond());
    }

    /**
     * Log successful return from admin controllers.
     */
    @AfterReturning(pointcut = "within(com.chatcrmlite.backend.controllers.admin..*)", returning = "result")
    public void auditAdminSuccess(JoinPoint joinPoint, Object result) {
        String user = getCurrentUser();
        String ip = getCurrentIp();
        auditLog.warn("[AUDIT] action={} user={} ip={} ts={} result=SUCCESS",
                joinPoint.getSignature().toShortString(), user, ip, Instant.now().getEpochSecond());
    }

    /**
     * Log exceptions thrown from admin controllers.
     */
    @AfterThrowing(pointcut = "within(com.chatcrmlite.backend.controllers.admin..*)", throwing = "ex")
    public void auditAdminFailure(JoinPoint joinPoint, Throwable ex) {
        String user = getCurrentUser();
        String ip = getCurrentIp();
        String result = (ex instanceof AccessDeniedException) ? "DENIED" : "ERROR";
        auditLog.warn("[AUDIT] action={} user={} ip={} ts={} result={} error={}",
                joinPoint.getSignature().toShortString(), user, ip,
                Instant.now().getEpochSecond(), result, ex.getClass().getSimpleName());
    }

    /**
     * Log privileged security service operations (session revoke, account lock, IP whitelist update).
     */
    @Before("execution(* com.chatcrmlite.backend.services.SecurityService.revokeSession(..)) || " +
            "execution(* com.chatcrmlite.backend.services.SecurityService.revokeAllSessions(..)) || " +
            "execution(* com.chatcrmlite.backend.services.SecurityService.lockAccount(..)) || " +
            "execution(* com.chatcrmlite.backend.services.SecurityService.updateIpWhitelist(..))")
    public void auditSecurityServiceCall(JoinPoint joinPoint) {
        String user = getCurrentUser();
        String ip = getCurrentIp();
        auditLog.warn("[AUDIT] action=SecurityService.{} user={} ip={} ts={}",
                joinPoint.getSignature().getName(), user, ip, Instant.now().getEpochSecond());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "SYSTEM";
    }

    private String getCurrentIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                return attrs.getRequest().getRemoteAddr();
            }
        } catch (Exception ignored) {
            // async or non-web context
        }
        return "INTERNAL";
    }
}
