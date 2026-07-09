package com.chatcrmlite.backend.services.platform;

import com.chatcrmlite.backend.models.PlatformAdmin;
import com.chatcrmlite.backend.models.PlatformAuditLog;
import com.chatcrmlite.backend.repositories.PlatformAdminRepository;
import com.chatcrmlite.backend.repositories.PlatformAuditLogRepository;
import com.chatcrmlite.backend.security.PlatformJwtUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Handles platform owner authentication with brute-force protection.
 *
 * Lockout policy:
 * - 5 consecutive failures → locked for 15 minutes
 * - Lock state persisted in DB (survives restarts)
 * - Success clears failed count
 */
@Service
public class PlatformAdminService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminService.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;
    private static final String COOKIE_NAME = "platform_token";

    private final PlatformAdminRepository adminRepository;
    private final PlatformAuditLogRepository auditRepository;
    private final PlatformJwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;

    public PlatformAdminService(PlatformAdminRepository adminRepository,
                                PlatformAuditLogRepository auditRepository,
                                PlatformJwtUtils jwtUtils,
                                PasswordEncoder passwordEncoder) {
        this.adminRepository = adminRepository;
        this.auditRepository = auditRepository;
        this.jwtUtils = jwtUtils;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Authenticates the owner, enforces lockout, sets HttpOnly cookie on success.
     *
     * @return Map with "status" → "ok" or "locked"/"invalid"
     */
    @Transactional
    public Map<String, Object> login(String email, String password,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        String ip = resolveIp(request);
        String ua = resolveUserAgent(request);
        String requestId = java.util.UUID.randomUUID().toString();

        PlatformAdmin admin = adminRepository.findByEmail(email).orElse(null);

        if (admin == null) {
            // Don't reveal whether account exists
            audit(requestId, "LOGIN", "FAILED", "System", email, "{\"reason\":\"unknown_email\"}", ip, ua);
            log.warn("[Platform] Login attempt for unknown email");
            return Map.of("status", "invalid", "message", "Invalid credentials");
        }

        if (admin.isCurrentlyLocked()) {
            long secs = admin.remainingLockSeconds();
            audit(requestId, "LOGIN", "FAILED", "System", email,
                  "{\"reason\":\"account_locked\",\"remainingSeconds\":" + secs + "}", ip, ua);
            return Map.of("status", "locked",
                          "message", "Account locked. Try again in " + Math.ceil(secs / 60.0) + " minutes.",
                          "remainingSeconds", secs);
        }

        if (!passwordEncoder.matches(password, admin.getPasswordHash())) {
            int newCount = admin.getFailedLoginCount() + 1;
            admin.setFailedLoginCount(newCount);

            if (newCount >= MAX_FAILED_ATTEMPTS) {
                admin.setLockedUntil(LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES));
                log.warn("[Platform] Account locked after {} failed attempts", newCount);
                audit(requestId, "LOGIN", "FAILED", "System", email,
                      "{\"reason\":\"max_attempts_reached\",\"lockMinutes\":" + LOCKOUT_MINUTES + "}", ip, ua);
                adminRepository.save(admin);
                return Map.of("status", "locked",
                              "message", "Too many failed attempts. Account locked for " + LOCKOUT_MINUTES + " minutes.",
                              "remainingSeconds", (long)(LOCKOUT_MINUTES * 60));
            }

            adminRepository.save(admin);
            audit(requestId, "LOGIN", "FAILED", "System", email,
                  "{\"reason\":\"bad_password\",\"attempt\":" + newCount + "}", ip, ua);
            return Map.of("status", "invalid", "message", "Invalid credentials",
                          "attemptsLeft", MAX_FAILED_ATTEMPTS - newCount);
        }

        // ── SUCCESS ───────────────────────────────────────────────────────────
        admin.setFailedLoginCount(0);
        admin.setLockedUntil(null);
        admin.setLastLogin(LocalDateTime.now());
        adminRepository.save(admin);

        String token = jwtUtils.generatePlatformToken(email);
        setSecureCookie(response, token);
        audit(requestId, "LOGIN", "SUCCESS", "System", email, "{}", ip, ua);

        return Map.of("status", "ok",
                      "displayName", admin.getDisplayName() != null ? admin.getDisplayName() : email,
                      "email", email);
    }

    /** Clears the platform cookie (logout). */
    public void logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(0); // delete immediately
        response.addCookie(cookie);
    }

    /** Changes the admin password. */
    @Transactional
    public boolean changePassword(String email, String currentPassword, String newPassword) {
        PlatformAdmin admin = adminRepository.findByEmail(email).orElseThrow();
        if (!passwordEncoder.matches(currentPassword, admin.getPasswordHash())) return false;
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        adminRepository.save(admin);
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setSecureCookie(HttpServletResponse response, String token) {
        // In development (localhost) Secure=true causes the browser to silently drop
        // the cookie because the connection is plain HTTP. We only set Secure in production.
        // SameSite=Strict is kept regardless to prevent CSRF.
        boolean isLocalDev = false; // override via env if needed
        try {
            String origin = System.getenv("ALLOWED_ORIGINS");
            isLocalDev = origin == null || origin.contains("localhost");
        } catch (Exception ignored) {}

        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);   // not accessible via JS
        cookie.setSecure(!isLocalDev); // HTTPS only in production
        cookie.setPath("/");
        cookie.setMaxAge(8 * 60 * 60); // 8 hours
        response.addCookie(cookie);
        // Also write via Set-Cookie header with SameSite=None for cross-origin requests (dev)
        String secureFlag = isLocalDev ? "" : "; Secure";
        response.addHeader("Set-Cookie",
            COOKIE_NAME + "=" + token + "; Path=/; HttpOnly" + secureFlag + "; SameSite=None; Max-Age=" + (8 * 3600));
    }

    private void audit(String requestId, String action, String outcome,
                       String targetType, String targetId, String detail,
                       String ip, String ua) {
        auditRepository.save(PlatformAuditLog.of(requestId, action, outcome,
                                                   targetType, targetId, detail, ip, ua));
    }

    private String resolveIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private String resolveUserAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return ua != null ? ua.substring(0, Math.min(ua.length(), 300)) : "unknown";
    }
}
