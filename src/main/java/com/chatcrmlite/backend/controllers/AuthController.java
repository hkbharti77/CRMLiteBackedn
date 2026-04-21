package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.SecurityLog;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.UserSession;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.UserSessionRepository;
import com.chatcrmlite.backend.security.JwtUtils;
import com.chatcrmlite.backend.services.EmailService;
import com.chatcrmlite.backend.services.SecurityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private EmailService emailService;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private SecurityService securityService;

    @PostMapping("/login")
    public ResponseEntity<?> initiateLogin(@RequestBody LoginRequest request) {
        emailService.generateAndSendOtp(request.getEmail());
        return ResponseEntity.ok("OTP sent to " + request.getEmail());
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verifyOtp(@RequestBody VerifyRequest request, HttpServletRequest servletRequest) {
        if (emailService.verifyOtp(request.getEmail(), request.getOtp())) {
            Optional<User> userOpt = userRepository.findByEmail(request.getEmail());
            User user;
            if (userOpt.isEmpty()) {
                user = User.builder()
                        .email(request.getEmail())
                        .businessName("My Business")
                        .onboardingCompleted(false)
                        .role(User.Role.OWNER)
                        .build();
                userRepository.save(user);
            } else {
                user = userOpt.get();
            }

            String sessionId = UUID.randomUUID().toString();
            String token = jwtUtils.generateJwtToken(user.getEmail(), sessionId);

            // Create and save session
            UserSession session = UserSession.builder()
                    .user(user)
                    .tokenId(sessionId)
                    .ipAddress(servletRequest.getRemoteAddr())
                    .deviceName(servletRequest.getHeader("User-Agent"))
                    .expiresAt(LocalDateTime.now().plusHours(24)) // Should match JWT expiration
                    .build();
            sessionRepository.save(session);

            // Log login event
            securityService.logSecurityEvent(user, SecurityLog.LogAction.LOGIN_SUCCESS, "SUCCESS", 
                    "Successful login via OTP", servletRequest.getRemoteAddr(), servletRequest.getHeader("User-Agent"));

            return ResponseEntity.ok(new AuthResponse(
                token,
                user.getId().toString(),
                user.getEmail(),
                user.getBusinessName(),
                user.getOnboardingCompleted() != null && user.getOnboardingCompleted()
            ));
        }
        
        // Log failure attempt if user exists
        userRepository.findByEmail(request.getEmail()).ifPresent(user -> 
            securityService.logSecurityEvent(user, SecurityLog.LogAction.LOGIN_FAILURE, "FAILURE", 
                    "Invalid OTP attempt", servletRequest.getRemoteAddr(), servletRequest.getHeader("User-Agent"))
        );

        return ResponseEntity.badRequest().body("Invalid or expired OTP");
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok("Successfully logged out");
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        private String email;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyRequest {
        private String email;
        private String otp;
    }

    @Data
    @AllArgsConstructor
    public static class AuthResponse {
        private String token;
        private String userId;        // Tenant UUID for WebSocket topic
        private String email;
        private String businessName;
        private boolean onboardingCompleted;
    }
}
