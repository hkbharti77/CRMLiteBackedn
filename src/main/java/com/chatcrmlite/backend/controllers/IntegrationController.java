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
            String url = googleCalendarService.buildAuthorizationUrl(user.getId());
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
            UUID userId = UUID.fromString(state);
            googleCalendarService.handleOAuthCallback(code, userId);
            log.info("[IntegrationController] Google OAuth callback success for userId={}", userId);
            // Redirect to frontend settings page
            return ResponseEntity.status(302)
                    .header("Location", frontendUrl + "/settings?googleConnected=true")
                    .build();
        } catch (Exception e) {
            log.error("[IntegrationController] OAuth callback failed", e);
            return ResponseEntity.status(302)
                    .header("Location", frontendUrl + "/settings?googleError=" + e.getMessage())
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
