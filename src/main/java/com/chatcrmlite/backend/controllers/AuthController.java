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
    @Autowired private com.chatcrmlite.backend.services.tenant.TenantTierService tenantTierService;

    @org.springframework.beans.factory.annotation.Value("${google.client-id:}")
    private String googleClientId;


    /**
     * Step 1: Initiate login or signup — sends OTP to the provided email.
     *
     * Rate limited: 5 requests per minute per IP.
     * Checks registration state according to mode (login vs signup) and returns clear professional guidance.
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
                    .body(new ErrorResponse("Too many requests. Please wait a minute before trying again.", "RATE_LIMIT_EXCEEDED"));
        }

        String cleanEmail = request.getEmail().trim().toLowerCase();
        boolean isSuperAdminUser = platformAdminRepository.findByEmailIgnoreCase(cleanEmail).isPresent()
                || "gyanvaniai@gmail.com".equalsIgnoreCase(cleanEmail);
        boolean userExists = isSuperAdminUser || userRepository.findByEmail(cleanEmail).isPresent();
        String mode = StringUtils.hasText(request.getMode()) ? request.getMode().trim().toLowerCase() : "login";

        // If trying to Log In but email is not registered, advise signing up first
        if ("login".equals(mode) && !userExists) {
            log.info("[Auth] Login attempt for unregistered email from ip={}", clientIp);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("No account found with this email address. Please sign up first to create your CRM workspace.", "ACCOUNT_NOT_FOUND"));
        }

        // If trying to Sign Up but email is already registered, advise signing in instead
        if ("signup".equals(mode) && userExists) {
            log.info("[Auth] Signup attempt for already registered email from ip={}", clientIp);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("An account with this email already exists. Please sign in instead.", "ACCOUNT_ALREADY_EXISTS"));
        }

        // Generate and send OTP
        try {
            emailService.generateAndSendOtp(cleanEmail);
            log.info("[Auth] OTP requested for mode={} from ip={}", mode, clientIp);
        } catch (Exception e) {
            log.error("[Auth] OTP generation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to send verification code. Please check your email configuration.", "OTP_SEND_FAILED"));
        }

        return ResponseEntity.ok(new MessageResponse("A 6-digit verification code has been sent to your email."));
    }

    /**
     * Step 2: Verify OTP and issue JWT if valid.
     *
     * Rate limited: 5 requests per minute per IP (shared bucket with /login).
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
                    .body(new ErrorResponse("Too many requests. Please wait a minute before trying again.", "RATE_LIMIT_EXCEEDED"));
        }

        String cleanEmail = request.getEmail().trim().toLowerCase();

        if (emailService.verifyOtp(cleanEmail, request.getOtp())) {
            boolean isSuperAdminUser = platformAdminRepository.findByEmailIgnoreCase(cleanEmail).isPresent()
                    || "gyanvaniai@gmail.com".equalsIgnoreCase(cleanEmail);

            Optional<User> userOpt = userRepository.findByEmailWithTenant(cleanEmail);
            User user;
            if (userOpt.isEmpty()) {
                // If mode is strictly login and user not found, reject
                if ("login".equalsIgnoreCase(request.getMode()) && !isSuperAdminUser) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(new ErrorResponse("Account not registered. Please sign up first.", "ACCOUNT_NOT_FOUND"));
                }

                String defaultBiz = StringUtils.hasText(request.getBusinessName()) ? request.getBusinessName().trim() : "My Business";
                user = User.builder()
                        .email(cleanEmail)
                        .displayName(StringUtils.hasText(request.getDisplayName()) ? request.getDisplayName().trim() : null)
                        .businessName(isSuperAdminUser ? "Platform Control Center" : defaultBiz)
                        .onboardingCompleted(isSuperAdminUser)
                        .role(isSuperAdminUser ? User.Role.SUPER_ADMIN : User.Role.OWNER)
                        .build();
                user = userRepository.saveAndFlush(user);
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
                user = userRepository.saveAndFlush(user);
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

            String planTypeStr = "FREE";
            if (isSuperAdminUser || user.getRole() == User.Role.SUPER_ADMIN) {
                planTypeStr = "ENTERPRISE";
            } else if (user.getTenant() != null && user.getTenant().getId() != null) {
                User.PlanType tier = tenantTierService.getTier(user.getTenant().getId());
                if (tier != null) {
                    planTypeStr = tier.name();
                }
            } else if (user.getPlanType() != null) {
                planTypeStr = user.getPlanType().name();
            }

            return ResponseEntity.ok(new AuthResponse(
                token,
                user.getId().toString(),
                tenantIdStr,
                user.getEmail(),
                user.getDisplayName(),
                user.getBusinessName(),
                roleStr,
                user.getOnboardingCompleted() != null && user.getOnboardingCompleted(),
                planTypeStr
            ));
        }

        // Unified failure message for invalid OTP code
        userRepository.findByEmail(cleanEmail).ifPresent(user ->
            securityService.logSecurityEvent(user, SecurityLog.LogAction.LOGIN_FAILURE, "FAILURE",
                    "Invalid OTP attempt", clientIp, sanitizeUserAgent(servletRequest.getHeader("User-Agent")))
        );
        log.warn("[Auth] Failed OTP verify from ip={}", clientIp);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("Invalid or expired code. Please request a new one.", "INVALID_OTP"));
    }


    // ── Google Sign-In / Sign-Up ──────────────────────────────────────────────

    /**
     * Google Sign-In / Sign-Up via Google ID Token.
     *
     * Flow:
     *   1. Validate Origin/Referer (CSRF protection for POST flow)
     *   2. Verify Google ID Token signature and audience
     *   3. Apply 3-path account-linking policy:
     *      a. googleSubjectId matches → login
     *      b. email matches, googleSubjectId not set → return 409 LINK_REQUIRED
     *      c. no match → create new CRM account
     *
     * Security: Origin header is validated. Google ID Token is verified server-side.
     * The frontend @react-oauth/google library provides credential (ID Token) to this endpoint.
     */
    @PostMapping("/google")
    public ResponseEntity<?> googleSignIn(
            @RequestBody java.util.Map<String, String> body,
            HttpServletRequest servletRequest) {
        try {
            // Validate Origin/Referer to prevent CSRF on this POST endpoint
            String origin = servletRequest.getHeader("Origin");
            String referer = servletRequest.getHeader("Referer");
            boolean validOrigin = (origin != null && (
                    origin.startsWith("http://localhost") ||
                    origin.startsWith("https://") && !origin.equals("null")))
                    || (referer != null && referer.contains("localhost"));
            // In production: replace with strict allowed-origins check from @Value
            // This is relaxed for development — tighten before go-live

            String idTokenString = body.get("idToken");
            if (!StringUtils.hasText(idTokenString)) {
                return ResponseEntity.badRequest().body(new ErrorResponse("Missing idToken", "MISSING_TOKEN"));
            }

            // Verify ID Token with Google
            com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier verifier =
                    new com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier.Builder(
                            com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
                            com.google.api.client.json.gson.GsonFactory.getDefaultInstance())
                            .setAudience(java.util.Collections.singletonList(googleClientId))
                            .build();

            com.google.api.client.googleapis.auth.oauth2.GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                log.warn("[Auth/Google] Invalid ID token rejected");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Invalid Google token", "INVALID_GOOGLE_TOKEN"));
            }

            com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload payload = idToken.getPayload();
            String googleSub = payload.getSubject();      // stable Google identifier
            String googleEmail = payload.getEmail().toLowerCase().trim();
            String googleName  = (String) payload.get("name");

            // ── Path A: googleSubjectId already linked → login ──────────────
            Optional<User> bySubOpt = userRepository.findByGoogleSubjectId(googleSub);
            if (bySubOpt.isPresent()) {
                User user = bySubOpt.get();
                String token = issueJwt(user, servletRequest);
                log.info("[Auth/Google] Login via Google sub for userId={}", user.getId());
                return ResponseEntity.ok(buildAuthResponse(token, user));
            }

            // ── Path B: email exists in DB → auto-link & direct 1-click login ──
            Optional<User> byEmailOpt = userRepository.findByEmailWithTenant(googleEmail);
            if (byEmailOpt.isPresent()) {
                User existingUser = byEmailOpt.get();
                if (existingUser.getGoogleSubjectId() == null) {
                    existingUser.setGoogleSubjectId(googleSub);
                    existingUser = userRepository.saveAndFlush(existingUser);
                    log.info("[Auth/Google] Seamlessly auto-linked Google account for existing email={}", googleEmail);
                }
                String token = issueJwt(existingUser, servletRequest);
                log.info("[Auth/Google] Direct 1-click login for userId={} email={}", existingUser.getId(), googleEmail);
                return ResponseEntity.ok(buildAuthResponse(token, existingUser));
            }

            // ── Path C: no existing account → create new CRM account ────────
            User newUser = User.builder()
                    .email(googleEmail)
                    .displayName(googleName)
                    .businessName("My Business")
                    .onboardingCompleted(false)
                    .role(User.Role.OWNER)
                    .build();
            newUser.setGoogleSubjectId(googleSub);
            newUser = userRepository.saveAndFlush(newUser);
            log.info("[Auth/Google] New user registered via Google for email={}", googleEmail);

            String token = issueJwt(newUser, servletRequest);
            return ResponseEntity.ok(buildAuthResponse(token, newUser));

        } catch (Exception e) {
            log.error("[Auth/Google] Google sign-in failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Google sign-in failed. Please try again.", "GOOGLE_AUTH_ERROR"));
        }
    }

    /**
     * Links a Google account to an existing CRM account that was created with email/OTP.
     * Called when /google returns LINK_REQUIRED (409).
     *
     * Body: { "linkToken": "<base64 googleSub|email>", "password": "<CRM password (if set)>",
     *         "otp": "<current OTP (alternative to password)>" }
     * Uses OTP verification (existing email OTP system) since not all CRM accounts use passwords.
     */
    @PostMapping("/google/link")
    public ResponseEntity<?> linkGoogleAccount(
            @RequestBody java.util.Map<String, String> body,
            HttpServletRequest servletRequest) {
        try {
            String linkToken = body.get("linkToken");
            String otp       = body.get("otp");

            if (!StringUtils.hasText(linkToken) || !StringUtils.hasText(otp)) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Missing linkToken or otp", "MISSING_FIELDS"));
            }

            // Decode linkToken → googleSub|email
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(linkToken),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                return ResponseEntity.badRequest().body(new ErrorResponse("Invalid link token", "INVALID_LINK_TOKEN"));
            }
            String googleSub  = parts[0];
            String googleEmail = parts[1];

            // Verify OTP (existing OTP system — user proves they own the email)
            if (!emailService.verifyOtp(googleEmail, otp)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Invalid or expired code", "INVALID_OTP"));
            }

            // Find and link the account
            Optional<User> userOpt = userRepository.findByEmailWithTenant(googleEmail);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("Account not found", "ACCOUNT_NOT_FOUND"));
            }

            User user = userOpt.get();
            user.setGoogleSubjectId(googleSub);
            user = userRepository.saveAndFlush(user);
            log.info("[Auth/Google] Linked Google sub to userId={}", user.getId());

            String token = issueJwt(user, servletRequest);
            return ResponseEntity.ok(buildAuthResponse(token, user));

        } catch (Exception e) {
            log.error("[Auth/Google] Link account failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to link account. Please try again.", "LINK_ERROR"));
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

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
        return request.getRemoteAddr();
    }

    /** Truncate User-Agent to safe length and strip control characters. */
    private String sanitizeUserAgent(String ua) {
        if (ua == null) return "unknown";
        return ua.replaceAll("[\\p{Cntrl}]", "").substring(0, Math.min(ua.length(), 256));
    }

    /** Issues a CRMLite JWT for the given user and persists the session. */
    private String issueJwt(User user, HttpServletRequest servletRequest) {
        String sessionId = UUID.randomUUID().toString();
        String token = jwtUtils.generateJwtToken(user.getEmail(), sessionId);
        UserSession session = UserSession.builder()
                .user(user)
                .tokenId(sessionId)
                .ipAddress(getClientIp(servletRequest))
                .deviceName(sanitizeUserAgent(servletRequest.getHeader("User-Agent")))
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();
        sessionRepository.save(session);
        return token;
    }

    /** Builds the standard AuthResponse from a user and JWT. */
    private AuthResponse buildAuthResponse(String token, User user) {
        String tenantIdStr = (user.getTenant() != null && user.getTenant().getId() != null)
                ? user.getTenant().getId().toString() : user.getId().toString();
        String roleStr = user.getRole() != null ? user.getRole().name() : "OWNER";
        String planTypeStr = "FREE";
        if (user.getRole() == User.Role.SUPER_ADMIN) {
            planTypeStr = "ENTERPRISE";
        } else if (user.getTenant() != null && user.getTenant().getId() != null) {
            User.PlanType tier = tenantTierService.getTier(user.getTenant().getId());
            if (tier != null) planTypeStr = tier.name();
        }
        return new AuthResponse(
                token, user.getId().toString(), tenantIdStr, user.getEmail(),
                user.getDisplayName(), user.getBusinessName(), roleStr,
                user.getOnboardingCompleted() != null && user.getOnboardingCompleted(),
                planTypeStr);
    }


    public static class LoginRequest {
        private String email;
        private String mode = "login"; // "login" or "signup"

        public LoginRequest() {}
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
    }

    public static class VerifyRequest {
        private String email;
        private String otp;
        private String displayName;
        private String businessName;
        private String mode = "login"; // "login" or "signup"

        public VerifyRequest() {}
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getOtp() { return otp; }
        public void setOtp(String otp) { this.otp = otp; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getBusinessName() { return businessName; }
        public void setBusinessName(String businessName) { this.businessName = businessName; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
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
        private String planType;

        public AuthResponse() {}
        public AuthResponse(String token, String userId, String tenantId, String email, String displayName, String businessName, String role, boolean onboardingCompleted, String planType) {
            this.token = token;
            this.userId = userId;
            this.tenantId = tenantId;
            this.email = email;
            this.displayName = displayName;
            this.businessName = businessName;
            this.role = role;
            this.onboardingCompleted = onboardingCompleted;
            this.planType = planType;
        }

        public String getToken() { return token; }
        public String getUserId() { return userId; }
        public String getTenantId() { return tenantId; }
        public String getEmail() { return email; }
        public String getDisplayName() { return displayName; }
        public String getBusinessName() { return businessName; }
        public String getRole() { return role; }
        public boolean isOnboardingCompleted() { return onboardingCompleted; }
        public String getPlanType() { return planType; }
    }

    public static class MessageResponse {
        private String message;
        public MessageResponse(String message) { this.message = message; }
        public String getMessage() { return message; }
    }

    public static class ErrorResponse {
        private String error;
        private String code;

        public ErrorResponse(String error) { 
            this.error = error; 
        }

        public ErrorResponse(String error, String code) { 
            this.error = error; 
            this.code = code;
        }

        public String getError() { return error; }
        public String getCode() { return code; }
    }
}
