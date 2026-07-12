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
    private com.chatcrmlite.backend.services.EmailService emailService;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private com.chatcrmlite.backend.services.tenant.QuotaEnforcerService quotaEnforcerService;

    @GetMapping("/me")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<?> getCurrentUser(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmailWithTenant(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(UserProfileDto.from(user));
    }

    @PutMapping("/me")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> updateCurrentUser(
            @AuthenticationPrincipal String email,
            @RequestBody UpdateUserRequest request) {
        
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        if (request.getDisplayName() != null) user.setDisplayName(request.getDisplayName());
        if (request.getPhone() != null) user.setPhone(request.getPhone());
        
        if (user.getRole() == User.Role.OWNER || user.getRole() == User.Role.ADMIN) {
            if (request.getBusinessName() != null) user.setBusinessName(request.getBusinessName());
            if (request.getBusinessType() != null) user.setBusinessType(request.getBusinessType());
            if (request.getBusinessSubType() != null) user.setBusinessSubType(request.getBusinessSubType());
            if (request.getAddress() != null) user.setAddress(request.getAddress());
            if (request.getAboutUs() != null) user.setAboutUs(request.getAboutUs());
            if (request.getLatitude() != null) user.setLatitude(request.getLatitude());
            if (request.getLongitude() != null) user.setLongitude(request.getLongitude());
            if (request.getLogoUrl() != null) user.setLogoUrl(request.getLogoUrl());
            if (request.getPrimaryColor() != null) user.getTenant().setPrimaryColor(request.getPrimaryColor());
            if (request.getSecondaryColor() != null) user.getTenant().setSecondaryColor(request.getSecondaryColor());
            
            // Manual module overrides
            if (request.getForceShowBooking() != null) user.setForceShowBooking(request.getForceShowBooking());
            if (request.getForceShowAppointment() != null) user.setForceShowAppointment(request.getForceShowAppointment());
            if (request.getForceShowLeads() != null) user.setForceShowLeads(request.getForceShowLeads());
        }

        userRepository.save(user);

        return ResponseEntity.ok(UserProfileDto.from(user));
    }

    @PostMapping("/staff")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> createStaffUser(
            @AuthenticationPrincipal String callerEmail,
            @RequestBody CreateStaffRequest request) {
        
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Email is required."));
        }
        if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Display name is required."));
        }
        if (request.getRole() == null || request.getRole().isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("Role is required."));
        }

        User.Role targetRole;
        try {
            targetRole = User.Role.valueOf(request.getRole().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new MessageResponse("Invalid role. Must be ADMIN or AGENT."));
        }

        if (targetRole == User.Role.OWNER) {
            return ResponseEntity.badRequest().body(new MessageResponse("Cannot create a tenant owner."));
        }

        User caller = userRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new RuntimeException("Caller not found"));

        // Enforce employee seat limits
        quotaEnforcerService.verifyEmployeeSeatQuota(caller.getTenant().getId());

        // Admin role enforcement: Admin can only create AGENT, not ADMIN
        if (caller.getRole() == User.Role.ADMIN && targetRole == User.Role.ADMIN) {
            return ResponseEntity.status(403).body(new MessageResponse("Admins are only authorized to create Agents."));
        }

        // Check if email already exists in system
        if (userRepository.findByEmail(request.getEmail().trim().toLowerCase()).isPresent()) {
            return ResponseEntity.badRequest().body(new MessageResponse("User with this email already exists."));
        }

        User staffUser = User.builder()
                .email(request.getEmail().trim().toLowerCase())
                .displayName(request.getDisplayName().trim())
                .phone(request.getPhone() != null ? request.getPhone().trim() : null)
                .role(targetRole)
                .tenant(caller.getTenant())
                .accountStatus(User.AccountStatus.ACTIVE)
                .onboardingCompleted(true) // Automatically complete onboarding for new staff members
                .build();

        userRepository.save(staffUser);

        try {
            emailService.sendStaffWelcomeEmail(
                staffUser.getEmail(),
                staffUser.getDisplayName(),
                caller.getTenant().getBusinessName(),
                staffUser.getRole().name()
            );
        } catch (Exception e) {
            System.err.println("Failed to send welcome email to staff: " + e.getMessage());
        }

        return ResponseEntity.ok(UserProfileDto.from(staffUser));
    }

    @GetMapping("/me/security-dashboard")
    public ResponseEntity<?> getSecurityDashboard(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        return ResponseEntity.ok(SecurityDashboardDto.builder()
                .healthScore(securityService.calculateSecurityScore(user))
                .biometricsEnabled(user.getBiometricsEnabled())
                .loginAlertsEnabled(user.getLoginAlertsEnabled())
                .ipWhitelist(user.getIpWhitelist())
                .accountStatus(user.getAccountStatus().name())
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
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
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

    @GetMapping("/tenant-staff")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<?> getTenantStaff(@AuthenticationPrincipal String email) {
        User caller = userRepository.findByEmailWithTenant(email).orElseThrow();
        List<User> staff = userRepository.findAllByTenantWithTenant(caller.getTenant());
        return ResponseEntity.ok(staff.stream().map(UserProfileDto::from).collect(Collectors.toList()));
    }

    @DeleteMapping("/staff/{staffId}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<?> deleteStaffUser(@AuthenticationPrincipal String email, @PathVariable UUID staffId) {
        User caller = userRepository.findByEmail(email).orElseThrow();
        securityService.deleteStaffUser(caller, staffId);
        return ResponseEntity.ok("Staff member permanently deleted");
    }

    @PatchMapping("/staff/{staffId}/status")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> updateStaffStatus(
            @AuthenticationPrincipal String email,
            @PathVariable UUID staffId,
            @RequestParam User.AccountStatus status,
            @RequestParam(required = false) String reason) {
        User caller = userRepository.findByEmail(email).orElseThrow();
        securityService.updateStaffStatus(caller, staffId, status, reason);
        return ResponseEntity.ok("Staff member status updated to: " + status);
    }

    // ─── DTOS ─────────────────────────────────────────────────────────────

    public static class ExportDataDto {
        private UserProfileDto user;
        private List<Lead> leads;
        private List<Contact> contacts;
        private List<Message> messages;

        public ExportDataDto() {}
        public ExportDataDto(UserProfileDto user, List<Lead> leads, List<Contact> contacts, List<Message> messages) {
            this.user = user;
            this.leads = leads;
            this.contacts = contacts;
            this.messages = messages;
        }

        public UserProfileDto getUser() { return user; }
        public List<Lead> getLeads() { return leads; }
        public List<Contact> getContacts() { return contacts; }
        public List<Message> getMessages() { return messages; }

        public static ExportDataDtoBuilder builder() { return new ExportDataDtoBuilder(); }
        public static class ExportDataDtoBuilder {
            private UserProfileDto user;
            private List<Lead> leads;
            private List<Contact> contacts;
            private List<Message> messages;
            public ExportDataDtoBuilder user(UserProfileDto user) { this.user = user; return this; }
            public ExportDataDtoBuilder leads(List<Lead> leads) { this.leads = leads; return this; }
            public ExportDataDtoBuilder contacts(List<Contact> contacts) { this.contacts = contacts; return this; }
            public ExportDataDtoBuilder messages(List<Message> messages) { this.messages = messages; return this; }
            public ExportDataDto build() { return new ExportDataDto(user, leads, contacts, messages); }
        }
    }

    public static class SecurityDashboardDto {
        private int healthScore;
        private boolean biometricsEnabled;
        private boolean loginAlertsEnabled;
        private Set<String> ipWhitelist;
        private String accountStatus;

        public SecurityDashboardDto() {}
        public SecurityDashboardDto(int healthScore, boolean biometricsEnabled, boolean loginAlertsEnabled, Set<String> ipWhitelist, String accountStatus) {
            this.healthScore = healthScore;
            this.biometricsEnabled = biometricsEnabled;
            this.loginAlertsEnabled = loginAlertsEnabled;
            this.ipWhitelist = ipWhitelist;
            this.accountStatus = accountStatus;
        }

        public int getHealthScore() { return healthScore; }
        public boolean isBiometricsEnabled() { return biometricsEnabled; }
        public boolean isLoginAlertsEnabled() { return loginAlertsEnabled; }
        public Set<String> getIpWhitelist() { return ipWhitelist; }
        public String getAccountStatus() { return accountStatus; }

        public static SecurityDashboardDtoBuilder builder() { return new SecurityDashboardDtoBuilder(); }
        public static class SecurityDashboardDtoBuilder {
            private int healthScore;
            private boolean biometricsEnabled;
            private boolean loginAlertsEnabled;
            private Set<String> ipWhitelist;
            private String accountStatus;
            public SecurityDashboardDtoBuilder healthScore(int healthScore) { this.healthScore = healthScore; return this; }
            public SecurityDashboardDtoBuilder biometricsEnabled(boolean biometricsEnabled) { this.biometricsEnabled = biometricsEnabled; return this; }
            public SecurityDashboardDtoBuilder loginAlertsEnabled(boolean loginAlertsEnabled) { this.loginAlertsEnabled = loginAlertsEnabled; return this; }
            public SecurityDashboardDtoBuilder ipWhitelist(Set<String> ipWhitelist) { this.ipWhitelist = ipWhitelist; return this; }
            public SecurityDashboardDtoBuilder accountStatus(String accountStatus) { this.accountStatus = accountStatus; return this; }
            public SecurityDashboardDto build() { return new SecurityDashboardDto(healthScore, biometricsEnabled, loginAlertsEnabled, ipWhitelist, accountStatus); }
        }
    }

    public static class SessionDto {
        private UUID id;
        private String deviceName;
        private String ipAddress;
        private String lastActiveAt;

        public SessionDto() {}
        public SessionDto(UUID id, String deviceName, String ipAddress, String lastActiveAt) {
            this.id = id;
            this.deviceName = deviceName;
            this.ipAddress = ipAddress;
            this.lastActiveAt = lastActiveAt;
        }

        public UUID getId() { return id; }
        public String getDeviceName() { return deviceName; }
        public String getIpAddress() { return ipAddress; }
        public String getLastActiveAt() { return lastActiveAt; }

        public static SessionDto from(UserSession s) {
            return SessionDto.builder()
                    .id(s.getId())
                    .deviceName(s.getDeviceName())
                    .ipAddress(s.getIpAddress())
                    .lastActiveAt(s.getLastActiveAt().toString())
                    .build();
        }

        public static SessionDtoBuilder builder() { return new SessionDtoBuilder(); }
        public static class SessionDtoBuilder {
            private UUID id;
            private String deviceName;
            private String ipAddress;
            private String lastActiveAt;
            public SessionDtoBuilder id(UUID id) { this.id = id; return this; }
            public SessionDtoBuilder deviceName(String deviceName) { this.deviceName = deviceName; return this; }
            public SessionDtoBuilder ipAddress(String ipAddress) { this.ipAddress = ipAddress; return this; }
            public SessionDtoBuilder lastActiveAt(String lastActiveAt) { this.lastActiveAt = lastActiveAt; return this; }
            public SessionDto build() { return new SessionDto(id, deviceName, ipAddress, lastActiveAt); }
        }
    }

    public static class SecuritySettingsRequest {
        private Boolean biometricsEnabled;
        private Boolean loginAlertsEnabled;
        private Set<String> ipWhitelist;

        public SecuritySettingsRequest() {}
        public Boolean getBiometricsEnabled() { return biometricsEnabled; }
        public void setBiometricsEnabled(Boolean biometricsEnabled) { this.biometricsEnabled = biometricsEnabled; }
        public Boolean getLoginAlertsEnabled() { return loginAlertsEnabled; }
        public void setLoginAlertsEnabled(Boolean loginAlertsEnabled) { this.loginAlertsEnabled = loginAlertsEnabled; }
        public Set<String> getIpWhitelist() { return ipWhitelist; }
        public void setIpWhitelist(Set<String> ipWhitelist) { this.ipWhitelist = ipWhitelist; }
    }

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
        private String primaryColor;
        private String secondaryColor;
        private Boolean forceShowBooking;
        private Boolean forceShowAppointment;
        private Boolean forceShowLeads;

        public UpdateUserRequest() {}
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getBusinessName() { return businessName; }
        public void setBusinessName(String businessName) { this.businessName = businessName; }
        public String getBusinessType() { return businessType; }
        public void setBusinessType(String businessType) { this.businessType = businessType; }
        public String getBusinessSubType() { return businessSubType; }
        public void setBusinessSubType(String businessSubType) { this.businessSubType = businessSubType; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getAboutUs() { return aboutUs; }
        public void setAboutUs(String aboutUs) { this.aboutUs = aboutUs; }
        public Double getLatitude() { return latitude; }
        public void setLatitude(Double latitude) { this.latitude = latitude; }
        public Double getLongitude() { return longitude; }
        public void setLongitude(Double longitude) { this.longitude = longitude; }
        public String getLogoUrl() { return logoUrl; }
        public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
        public String getPrimaryColor() { return primaryColor; }
        public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
        public String getSecondaryColor() { return secondaryColor; }
        public void setSecondaryColor(String secondaryColor) { this.secondaryColor = secondaryColor; }
        public Boolean getForceShowBooking() { return forceShowBooking; }
        public void setForceShowBooking(Boolean forceShowBooking) { this.forceShowBooking = forceShowBooking; }
        public Boolean getForceShowAppointment() { return forceShowAppointment; }
        public void setForceShowAppointment(Boolean forceShowAppointment) { this.forceShowAppointment = forceShowAppointment; }
        public Boolean getForceShowLeads() { return forceShowLeads; }
        public void setForceShowLeads(Boolean forceShowLeads) { this.forceShowLeads = forceShowLeads; }
    }

    public static class UserProfileDto {
        private String id;
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
        private String primaryColor;
        private String secondaryColor;
        private Boolean forceShowBooking;
        private Boolean forceShowAppointment;
        private Boolean forceShowLeads;
        private String role;
        private String accountStatus;
        private String planType;

        public UserProfileDto() {}
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getBusinessName() { return businessName; }
        public void setBusinessName(String businessName) { this.businessName = businessName; }
        public String getBusinessType() { return businessType; }
        public void setBusinessType(String businessType) { this.businessType = businessType; }
        public String getBusinessSubType() { return businessSubType; }
        public void setBusinessSubType(String businessSubType) { this.businessSubType = businessSubType; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getAboutUs() { return aboutUs; }
        public void setAboutUs(String aboutUs) { this.aboutUs = aboutUs; }
        public Double getLatitude() { return latitude; }
        public void setLatitude(Double latitude) { this.latitude = latitude; }
        public Double getLongitude() { return longitude; }
        public void setLongitude(Double longitude) { this.longitude = longitude; }
        public String getLogoUrl() { return logoUrl; }
        public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
        public String getPrimaryColor() { return primaryColor; }
        public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
        public String getSecondaryColor() { return secondaryColor; }
        public void setSecondaryColor(String secondaryColor) { this.secondaryColor = secondaryColor; }
        public Boolean getForceShowBooking() { return forceShowBooking; }
        public void setForceShowBooking(Boolean forceShowBooking) { this.forceShowBooking = forceShowBooking; }
        public Boolean getForceShowAppointment() { return forceShowAppointment; }
        public void setForceShowAppointment(Boolean forceShowAppointment) { this.forceShowAppointment = forceShowAppointment; }
        public Boolean getForceShowLeads() { return forceShowLeads; }
        public void setForceShowLeads(Boolean forceShowLeads) { this.forceShowLeads = forceShowLeads; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getAccountStatus() { return accountStatus; }
        public void setAccountStatus(String accountStatus) { this.accountStatus = accountStatus; }
        public String getPlanType() { return planType; }
        public void setPlanType(String planType) { this.planType = planType; }

        public static UserProfileDto from(User user) {
            UserProfileDto dto = new UserProfileDto();
            dto.setId(user.getId().toString());
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
            dto.setPrimaryColor(user.getTenant() != null ? user.getTenant().getPrimaryColor() : null);
            dto.setSecondaryColor(user.getTenant() != null ? user.getTenant().getSecondaryColor() : null);
            dto.setForceShowBooking(user.getForceShowBooking());
            dto.setForceShowAppointment(user.getForceShowAppointment());
            dto.setForceShowLeads(user.getForceShowLeads());
            dto.setRole(user.getRole() != null ? user.getRole().name() : null);
            dto.setAccountStatus(user.getAccountStatus() != null ? user.getAccountStatus().name() : null);
            dto.setPlanType(user.getPlanType() != null ? user.getPlanType().name() : "FREE");
            return dto;
        }
    }

    public static class CreateStaffRequest {
        private String email;
        private String displayName;
        private String role;
        private String phone;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
    }

    public static class MessageResponse {
        private String message;
        public MessageResponse(String message) { this.message = message; }
        public String getMessage() { return message; }
    }
}

