package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.models.PlatformAdmin;
import com.chatcrmlite.backend.repositories.PlatformAdminRepository;
import com.chatcrmlite.backend.services.platform.PlatformAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Platform authentication endpoints.
 * /api/v1/platform/auth/login is the only unauthenticated endpoint.
 * All others require the platform JWT cookie (enforced by PlatformAuthFilter).
 */
@RestController
@RequestMapping("/api/v1/platform/auth")
public class PlatformAuthController {

    private final PlatformAdminService adminService;
    private final PlatformAdminRepository adminRepository;

    public PlatformAuthController(PlatformAdminService adminService,
                                  PlatformAdminRepository adminRepository) {
        this.adminService = adminService;
        this.adminRepository = adminRepository;
    }

    public static class PlatformLoginRequest {
        private String email;
        private String password;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody PlatformLoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {

        String email = body.getEmail();
        String password = body.getPassword();

        if (email == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "invalid", "message", "Email and password required"));
        }

        Map<String, Object> result = adminService.login(email, password, request, response);

        String status = (String) result.get("status");
        if ("ok".equals(status)) {
            return ResponseEntity.ok(result);
        } else if ("locked".equals(status)) {
            return ResponseEntity.status(423).body(result);
        } else {
            return ResponseEntity.status(401).body(result);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        adminService.logout(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication auth) {
        String email = auth.getName();
        PlatformAdmin admin = adminRepository.findByEmail(email).orElseThrow();
        return ResponseEntity.ok(Map.of(
            "email", admin.getEmail(),
            "displayName", admin.getDisplayName() != null ? admin.getDisplayName() : "",
            "lastLogin", admin.getLastLogin() != null ? admin.getLastLogin().toString() : ""
        ));
    }

    public static class ChangePasswordRequest {
        private String currentPassword;
        private String newPassword;

        public String getCurrentPassword() { return currentPassword; }
        public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    }

    @PutMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody ChangePasswordRequest body, Authentication auth) {
        String current = body.getCurrentPassword();
        String next = body.getNewPassword();
        if (current == null || next == null || next.length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("message", "New password must be at least 8 characters"));
        }
        boolean ok = adminService.changePassword(auth.getName(), current, next);
        return ok
            ? ResponseEntity.ok(Map.of("message", "Password updated successfully"))
            : ResponseEntity.status(403).body(Map.of("message", "Current password incorrect"));
    }
}
