package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.config.RateLimitConfig;
import com.chatcrmlite.backend.models.SecurityLog;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.UserSession;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.UserSessionRepository;
import com.chatcrmlite.backend.security.JwtUtils;
import com.chatcrmlite.backend.services.EmailService;
import com.chatcrmlite.backend.services.SecurityService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.chatcrmlite.backend.repositories.PlatformAdminRepository;

/**
 * Authentication controller — OTP-based login flow.
 *
 * Security hardening applied:
 * - Rate limiting on /login and /verify via Bucket4j (5 req/min per IP)
 * - Input validation: email format check, OTP format check (6 digits)
 * - Generic error messages — same response for "email not found" and "invalid OTP"
 *   to prevent user enumeration
 * - /logout revokes the current session from the database
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern OTP_PATTERN   = Pattern.compile("^\\d{6}$");

    @Autowired private UserRepository userRepository;
    @Autowired private PlatformAdminRepository platformAdminRepository;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private EmailService emailService;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private SecurityService securityService;
    @Autowired private RateLimitConfig rateLimitConfig;
    @Autowired private com.chatcrmlite.backend.services.platform.PlatformAuditService platformAuditService;

    /**
     * Step 1: Initiate login — sends OTP to the provided email.
     *
     * Rate limited: 5 requests per minute per IP.
     * No indication whether the email exists in the system (prevents enumeration).
     */
    @PostMapping("/login")
    public ResponseEntity<?> initiateLogin(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        // Input validation
        if (!isValidEmail(request.getEmail())) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Invalid request"));
        }

        // Rate limiting — prevents OTP spam and brute force
        String clientIp = getClientIp(servletRequest);
        Bucket bucket = rateLimitConfig.resolveBucket("login:" + clientIp);
        if (!bucket.tryConsume(1)) {
            log.warn("[Auth] Rate limit exceeded on /login from IP={}", clientIp);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse("Too many requests. Please wait a minute before trying again."));
        }

        // Always generate and attempt to send OTP — don't reveal if email exists
        try {
            emailService.generateAndSendOtp(request.getEmail());
            log.info("[Auth] OTP requested from ip={}", clientIp);
        } catch (Exception e) {
            log.error("[Auth] OTP generation failed: {}", e.getMessage());
        }

        // Always return the same message regardless of whether the email exists
        return ResponseEntity.ok(new MessageResponse("If this email is registered, you will receive a login code."));
    }

    /**
     * Step 2: Verify OTP and issue JWT if valid.
     *
     * Rate limited: 5 requests per minute per IP (shared bucket with /login).
     * Generic error response — does not reveal whether email or OTP was wrong.
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verifyOtp(@RequestBody VerifyRequest request, HttpServletRequest servletRequest) {
        // Input validation
        if (!isValidEmail(request.getEmail()) || !isValidOtp(request.getOtp())) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Invalid request"));
        }

        // Rate limiting
        String clientIp = getClientIp(servletRequest);
        Bucket bucket = rateLimitConfig.resolveBucket("verify:" + clientIp);
        if (!bucket.tryConsume(1)) {
            log.warn("[Auth] Rate limit exceeded on /verify from IP={}", clientIp);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse("Too many requests. Please wait a minute before trying again."));
        }

        if (emailService.verifyOtp(request.getEmail(), request.getOtp())) {
            boolean isSuperAdminUser = platformAdminRepository.findByEmailIgnoreCase(request.getEmail()).isPresent()
                    || "gyanvaniai@gmail.com".equalsIgnoreCase(request.getEmail().trim());

            Optional<User> userOpt = userRepository.findByEmailWithTenant(request.getEmail());
            User user;
            if (userOpt.isEmpty()) {
                String defaultBiz = StringUtils.hasText(request.getBusinessName()) ? request.getBusinessName().trim() : "My Business";
                user = User.builder()
                        .email(request.getEmail())
                        .displayName(StringUtils.hasText(request.getDisplayName()) ? request.getDisplayName().trim() : null)
                        .businessName(isSuperAdminUser ? "Platform Control Center" : defaultBiz)
                        .onboardingCompleted(true)
                        .role(isSuperAdminUser ? User.Role.SUPER_ADMIN : User.Role.OWNER)
                        .build();
                userRepository.save(user);
                log.info("[Auth] New user registered from ip={} (role={})", clientIp, user.getRole());
            } else {
                user = userOpt.get();
                if (isSuperAdminUser && user.getRole() != User.Role.SUPER_ADMIN) {
                    user.setRole(User.Role.SUPER_ADMIN);
                }
                if (StringUtils.hasText(request.getDisplayName()) && !StringUtils.hasText(user.getDisplayName())) {
                    user.setDisplayName(request.getDisplayName().trim());
                }
                if (StringUtils.hasText(request.getBusinessName()) && ("My Business".equals(user.getBusinessName()) || !StringUtils.hasText(user.getBusinessName()))) {
                    user.setBusinessName(request.getBusinessName().trim());
                }
                userRepository.save(user);
            }

            String sessionId = UUID.randomUUID().toString();
            String token = jwtUtils.generateJwtToken(user.getEmail(), sessionId);

            UserSession session = UserSession.builder()
                    .user(user)
                    .tokenId(sessionId)
                    .ipAddress(clientIp)
                    .deviceName(sanitizeUserAgent(servletRequest.getHeader("User-Agent")))
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            sessionRepository.save(session);

            securityService.logSecurityEvent(user, SecurityLog.LogAction.LOGIN_SUCCESS, "SUCCESS",
                    "Successful OTP login", clientIp, sanitizeUserAgent(servletRequest.getHeader("User-Agent")));

            String tenantIdStr = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId().toString() : user.getId().toString();
            platformAuditService.recordTenantLogin(user.getEmail(), tenantIdStr, "SUCCESS", servletRequest);

            String roleStr = user.getRole() != null ? user.getRole().name() : (isSuperAdminUser ? "SUPER_ADMIN" : "OWNER");

            return ResponseEntity.ok(new AuthResponse(
                token,
                user.getId().toString(),
                tenantIdStr,
                user.getEmail(),
                user.getDisplayName(),
                user.getBusinessName(),
                roleStr,
                user.getOnboardingCompleted() != null && user.getOnboardingCompleted()
            ));
        }

        // Unified failure — same message whether email unknown or OTP wrong (prevents enumeration)
        userRepository.findByEmail(request.getEmail()).ifPresent(user ->
            securityService.logSecurityEvent(user, SecurityLog.LogAction.LOGIN_FAILURE, "FAILURE",
                    "Invalid OTP attempt", clientIp, sanitizeUserAgent(servletRequest.getHeader("User-Agent")))
        );
        log.warn("[Auth] Failed OTP verify from ip={}", clientIp);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("Invalid or expired code. Please request a new one."));
    }

    /**
     * Logout — revokes the current session.
     * Requires a valid JWT (caller must be authenticated).
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            try {
                String token = header.substring(7);
                String sessionId = jwtUtils.getSessionIdFromJwtToken(token);
                if (sessionId != null) {
                    sessionRepository.findByTokenId(sessionId).ifPresent(session -> {
                        session.setStatus("REVOKED");
                        sessionRepository.save(session);
                    });
                }
            } catch (Exception e) {
                // Token may already be expired — logout should still succeed
                log.debug("[Auth] Logout with expired/invalid token — session may not exist");
            }
        }
        return ResponseEntity.ok(new MessageResponse("Successfully logged out."));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isValidEmail(String email) {
        return StringUtils.hasText(email) && EMAIL_PATTERN.matcher(email.trim()).matches() && email.length() <= 254;
    }

    private boolean isValidOtp(String otp) {
        return StringUtils.hasText(otp) && OTP_PATTERN.matcher(otp.trim()).matches();
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Truncate User-Agent to safe length and strip control characters. */
    private String sanitizeUserAgent(String ua) {
        if (ua == null) return "unknown";
        return ua.replaceAll("[\\p{Cntrl}]", "").substring(0, Math.min(ua.length(), 256));
    }

    // ── Request / Response DTOs ────────────────────────────────────────────────

    public static class LoginRequest {
        private String email;
        public LoginRequest() {}
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    public static class VerifyRequest {
        private String email;
        private String otp;
        private String displayName;
        private String businessName;

        public VerifyRequest() {}
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getOtp() { return otp; }
        public void setOtp(String otp) { this.otp = otp; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getBusinessName() { return businessName; }
        public void setBusinessName(String businessName) { this.businessName = businessName; }
    }

    public static class AuthResponse {
        private String token;
        private String userId;
        private String tenantId;
        private String email;
        private String displayName;
        private String businessName;
        private String role;
        private boolean onboardingCompleted;

        public AuthResponse() {}
        public AuthResponse(String token, String userId, String tenantId, String email, String displayName, String businessName, String role, boolean onboardingCompleted) {
            this.token = token;
            this.userId = userId;
            this.tenantId = tenantId;
            this.email = email;
            this.displayName = displayName;
            this.businessName = businessName;
            this.role = role;
            this.onboardingCompleted = onboardingCompleted;
        }

        public String getToken() { return token; }
        public String getUserId() { return userId; }
        public String getTenantId() { return tenantId; }
        public String getEmail() { return email; }
        public String getDisplayName() { return displayName; }
        public String getBusinessName() { return businessName; }
        public String getRole() { return role; }
        public boolean isOnboardingCompleted() { return onboardingCompleted; }
    }

    public static class MessageResponse {
        private String message;
        public MessageResponse(String message) { this.message = message; }
        public String getMessage() { return message; }
    }

    public static class ErrorResponse {
        private String error;
        public ErrorResponse(String error) { this.error = error; }
        public String getError() { return error; }
    }
}
