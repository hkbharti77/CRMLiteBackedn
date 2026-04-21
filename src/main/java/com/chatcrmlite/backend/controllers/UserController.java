package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.UserSession;
import com.chatcrmlite.backend.models.SecurityLog;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.UserSessionRepository;
import com.chatcrmlite.backend.repositories.SecurityLogRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.services.SecurityService;
import lombok.Builder;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private SecurityLogRepository logRepository;

    @Autowired
    private SecurityService securityService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MessageRepository messageRepository;

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(UserProfileDto.from(user));
    }

    @PutMapping("/me")
    public ResponseEntity<?> updateCurrentUser(
            @AuthenticationPrincipal String email,
            @RequestBody UpdateUserRequest request) {
        
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        if (request.getDisplayName() != null) user.setDisplayName(request.getDisplayName());
        if (request.getPhone() != null) user.setPhone(request.getPhone());
        if (request.getBusinessName() != null) user.setBusinessName(request.getBusinessName());
        if (request.getBusinessType() != null) user.setBusinessType(request.getBusinessType());
        if (request.getBusinessSubType() != null) user.setBusinessSubType(request.getBusinessSubType());
        if (request.getAddress() != null) user.setAddress(request.getAddress());
        if (request.getAboutUs() != null) user.setAboutUs(request.getAboutUs());
        if (request.getLatitude() != null) user.setLatitude(request.getLatitude());
        if (request.getLongitude() != null) user.setLongitude(request.getLongitude());
        if (request.getLogoUrl() != null) user.setLogoUrl(request.getLogoUrl());

        userRepository.save(user);

        return ResponseEntity.ok(UserProfileDto.from(user));
    }

    // ─── SECURITY SUITE ENDPOINTS ──────────────────────────────────────────

    @GetMapping("/me/security-dashboard")
    public ResponseEntity<?> getSecurityDashboard(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        return ResponseEntity.ok(SecurityDashboardDto.builder()
                .healthScore(securityService.calculateSecurityScore(user))
                .biometricsEnabled(user.getBiometricsEnabled())
                .loginAlertsEnabled(user.getLoginAlertsEnabled())
                .ipWhitelist(user.getIpWhitelist())
                .accountStatus(user.getAccountStatus())
                .build());
    }

    @GetMapping("/me/sessions")
    public ResponseEntity<?> getSessions(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        List<UserSession> sessions = sessionRepository.findByUserAndStatus(user, "ACTIVE");
        return ResponseEntity.ok(sessions.stream().map(SessionDto::from).collect(Collectors.toList()));
    }

    @GetMapping("/me/security-logs")
    public ResponseEntity<?> getSecurityLogs(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        List<SecurityLog> logs = logRepository.findByUserOrderByTimestampDesc(user);
        return ResponseEntity.ok(logs);
    }

    @DeleteMapping("/me/sessions/{sessionId}")
    public ResponseEntity<?> revokeSession(@AuthenticationPrincipal String email, @PathVariable UUID sessionId) {
        User user = userRepository.findByEmail(email).orElseThrow();
        UserSession session = sessionRepository.findById(sessionId).orElseThrow();
        if (!session.getUser().getId().equals(user.getId())) return ResponseEntity.status(403).build();
        
        securityService.revokeSession(sessionId);
        return ResponseEntity.ok("Session revoked");
    }

    @DeleteMapping("/me/sessions")
    public ResponseEntity<?> revokeAllSessions(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        securityService.revokeAllSessions(user);
        return ResponseEntity.ok("All sessions revoked");
    }

    @PatchMapping("/me/security-settings")
    public ResponseEntity<?> updateSettings(@AuthenticationPrincipal String email, @RequestBody SecuritySettingsRequest request) {
        User user = userRepository.findByEmail(email).orElseThrow();
        if (request.getBiometricsEnabled() != null) user.setBiometricsEnabled(request.getBiometricsEnabled());
        if (request.getLoginAlertsEnabled() != null) user.setLoginAlertsEnabled(request.getLoginAlertsEnabled());
        if (request.getIpWhitelist() != null) user.setIpWhitelist(request.getIpWhitelist());
        
        userRepository.save(user);
        securityService.logSecurityEvent(user, SecurityLog.LogAction.BIOMETRICS_TOGGLED, "SUCCESS", "Security settings updated", null, null);
        return ResponseEntity.ok("Settings updated");
    }

    @PostMapping("/me/kill-switch")
    public ResponseEntity<?> killSwitch(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        securityService.lockAccount(user);
        return ResponseEntity.ok("Account locked and all sessions revoked");
    }

    @PostMapping("/me/recover-leads")
    public ResponseEntity<?> recoverLeads(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        List<Lead> deletedLeads = leadRepository.findAllByOwnerAndDeletedTrue(user);
        deletedLeads.forEach(lead -> lead.setDeleted(false));
        leadRepository.saveAll(deletedLeads);
        securityService.logSecurityEvent(user, SecurityLog.LogAction.SESSIONS_REVOKED, "SUCCESS", "Recovered " + deletedLeads.size() + " leads", null, null);
        return ResponseEntity.ok("Recovered " + deletedLeads.size() + " leads");
    }

    @GetMapping("/me/export-data")
    public ResponseEntity<?> exportData(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        List<Lead> leads = leadRepository.findAllByOwner(user);
        List<Contact> contacts = contactRepository.findAllByOwner(user);
        List<Message> messages = messageRepository.findAllByContactIn(contacts);

        return ResponseEntity.ok(ExportDataDto.builder()
                .user(UserProfileDto.from(user))
                .leads(leads)
                .contacts(contacts)
                .messages(messages)
                .build());
    }

    // ─── DTOS ─────────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class ExportDataDto {
        private UserProfileDto user;
        private List<Lead> leads;
        private List<Contact> contacts;
        private List<Message> messages;
    }

    @Data
    @Builder
    public static class SecurityDashboardDto {
        private int healthScore;
        private boolean biometricsEnabled;
        private boolean loginAlertsEnabled;
        private Set<String> ipWhitelist;
        private String accountStatus;
    }

    @Data
    @Builder
    public static class SessionDto {
        private UUID id;
        private String deviceName;
        private String ipAddress;
        private String lastActiveAt;

        public static SessionDto from(UserSession s) {
            return SessionDto.builder()
                    .id(s.getId())
                    .deviceName(s.getDeviceName())
                    .ipAddress(s.getIpAddress())
                    .lastActiveAt(s.getLastActiveAt().toString())
                    .build();
        }
    }

    @Data
    public static class SecuritySettingsRequest {
        private Boolean biometricsEnabled;
        private Boolean loginAlertsEnabled;
        private Set<String> ipWhitelist;
    }

    @Data
    public static class UpdateUserRequest {
        private String displayName;
        private String phone;
        private String businessName;
        private String businessType;
        private String businessSubType;
        private String address;
        private String aboutUs;
        private Double latitude;
        private Double longitude;
        private String logoUrl;
    }

    @Data
    public static class UserProfileDto {
        private String email;
        private String displayName;
        private String phone;
        private String businessName;
        private String businessType;
        private String businessSubType;
        private String address;
        private String aboutUs;
        private Double latitude;
        private Double longitude;
        private String logoUrl;

        public static UserProfileDto from(User user) {
            UserProfileDto dto = new UserProfileDto();
            dto.setEmail(user.getEmail());
            dto.setDisplayName(user.getDisplayName());
            dto.setPhone(user.getPhone());
            dto.setBusinessName(user.getBusinessName());
            dto.setBusinessType(user.getBusinessType());
            dto.setBusinessSubType(user.getBusinessSubType());
            dto.setAddress(user.getAddress());
            dto.setAboutUs(user.getAboutUs());
            dto.setLatitude(user.getLatitude());
            dto.setLongitude(user.getLongitude());
            dto.setLogoUrl(user.getLogoUrl());
            return dto;
        }
    }
}
