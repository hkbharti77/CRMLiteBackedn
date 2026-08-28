package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.GoogleCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.Base64;
import java.time.Duration;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/**
 * Handles Google OAuth integration for connecting users' Google accounts
 * to generate Google Meet links for appointments.
 */
@RestController
@RequestMapping("/api/v1/integrations/google")
public class IntegrationController {

    private static final Logger log = LoggerFactory.getLogger(IntegrationController.class);

    @Autowired private GoogleCalendarService googleCalendarService;
    @Autowired private UserRepository userRepository;
    @Autowired private RedissonClient redissonClient;
    
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${FRONTEND_URL:http://localhost:3000}")
    private String frontendUrl;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * Returns the Google OAuth URL that the frontend should redirect the user to.
     */
    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl() {
        try {
            User user = getAuthenticatedUser();
            
            // Generate a secure random state
            byte[] stateBytes = new byte[32];
            secureRandom.nextBytes(stateBytes);
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
            
            // Store the state mapped to the user ID in Redis with a 10-minute TTL
            RBucket<UUID> stateBucket = redissonClient.getBucket("oauth:state:" + state);
            stateBucket.set(user.getId(), Duration.ofMinutes(10));

            String url = googleCalendarService.buildAuthorizationUrl(state);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            log.error("[IntegrationController] Failed to build auth URL", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to build Google authorization URL: " + e.getMessage()));
        }
    }

    /**
     * Google OAuth callback endpoint.
     * Receives the authorization code, exchanges it for tokens, and saves them.
     * Then redirects the frontend to the settings/integrations page.
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> oauthCallback(
            @RequestParam String code,
            @RequestParam(required = false) String state) {
        try {
            if (state == null || state.isBlank()) {
                throw new IllegalArgumentException("Missing OAuth state");
            }

            // Atomically fetch and delete the state to prevent replay attacks
            RBucket<UUID> stateBucket = redissonClient.getBucket("oauth:state:" + state);
            UUID userId = stateBucket.getAndDelete();

            if (userId == null) {
                log.warn("[IntegrationController] Invalid, expired, or consumed OAuth state provided");
                throw new SecurityException("Invalid or expired OAuth state");
            }

            googleCalendarService.handleOAuthCallback(code, userId);
            log.info("[IntegrationController] Google OAuth callback success for userId={}", userId);
            // Redirect to frontend settings page
            return ResponseEntity.status(302)
                    .header("Location", frontendUrl + "/settings?googleConnected=true")
                    .build();
        } catch (Exception e) {
            log.error("[IntegrationController] OAuth callback failed", e);
            
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
            try {
                errorMessage = java.net.URLEncoder.encode(errorMessage, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}

            return ResponseEntity.status(302)
                    .header("Location", frontendUrl + "/settings?googleError=" + errorMessage)
                    .build();
        }
    }

    /**
     * Returns whether the current user has connected their Google account.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> getStatus() {
        User user = getAuthenticatedUser();
        boolean connected = googleCalendarService.isConnected(user);
        return ResponseEntity.ok(Map.of("connected", connected));
    }

    /**
     * Disconnects the user's Google account by clearing stored tokens.
     */
    @DeleteMapping("/disconnect")
    public ResponseEntity<Map<String, String>> disconnect() {
        try {
            User user = getAuthenticatedUser();
            user.setGoogleAccessToken(null);
            user.setGoogleRefreshToken(null);
            user.setGoogleTokenExpiry(null);
            userRepository.save(user);
            log.info("[IntegrationController] Google disconnected for userId={}", user.getId());
            return ResponseEntity.ok(Map.of("message", "Google account disconnected successfully"));
        } catch (Exception e) {
            log.error("[IntegrationController] Failed to disconnect Google", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to disconnect: " + e.getMessage()));
        }
    }
}
