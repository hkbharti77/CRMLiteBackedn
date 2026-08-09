package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

@Entity
@Table(name = "app_users", indexes = {
    @Index(name = "idx_user_email", columnList = "email")
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private String password;

    @ManyToOne(fetch = FetchType.EAGER, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "tenant_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Tenant tenant;

    private LocalDateTime consentAt;
    private String displayName;
    private String phone;

    @Enumerated(EnumType.STRING)
    private Role role;

    public enum AvailabilityStatus {
        AVAILABLE, BUSY, OFFLINE
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    private AccountStatus accountStatus = AccountStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", columnDefinition = "VARCHAR(255) DEFAULT 'AVAILABLE'")
    private AvailabilityStatus availabilityStatus = AvailabilityStatus.AVAILABLE;

    @Column(name = "max_concurrent_chats", columnDefinition = "INT DEFAULT 2")
    private Integer maxConcurrentChats = 2;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_ip_whitelist", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "ip_address")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Set<String> ipWhitelist = new HashSet<>();

    private Boolean biometricsEnabled = false;
    private Boolean loginAlertsEnabled = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions", columnDefinition = "jsonb")
    private List<String> permissions = new ArrayList<>();

    @Version
    @Column(name = "permission_version", nullable = false)
    private Integer permissionVersion = 1;

    @Transient
    private WhatsAppConfig whatsappConfig;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private String googleAccessToken;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private String googleRefreshToken;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private LocalDateTime googleTokenExpiry;

    private LocalDateTime createdAt;

    public User() {}

    public User(UUID id, String email, String password, Tenant tenant, LocalDateTime consentAt, String displayName, String phone, Role role, AccountStatus accountStatus, Set<String> ipWhitelist, Boolean biometricsEnabled, Boolean loginAlertsEnabled, WhatsAppConfig whatsappConfig, String googleAccessToken, String googleRefreshToken, LocalDateTime googleTokenExpiry, LocalDateTime createdAt) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.tenant = tenant;
        this.consentAt = consentAt;
        this.displayName = displayName;
        this.phone = phone;
        this.role = role;
        this.accountStatus = accountStatus != null ? accountStatus : AccountStatus.ACTIVE;
        this.ipWhitelist = (ipWhitelist != null) ? ipWhitelist : new HashSet<>();
        this.biometricsEnabled = biometricsEnabled;
        this.loginAlertsEnabled = loginAlertsEnabled;
        this.whatsappConfig = whatsappConfig;
        this.googleAccessToken = googleAccessToken;
        this.googleRefreshToken = googleRefreshToken;
        this.googleTokenExpiry = googleTokenExpiry;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    private boolean isTenantInitialized() {
        return tenant != null && org.hibernate.Hibernate.isInitialized(tenant);
    }

    // ── Backward Compatibility Delegation Getters ──
    public String getBusinessName() { return isTenantInitialized() ? tenant.getBusinessName() : null; }
    public String getBusinessType() { return isTenantInitialized() ? tenant.getBusinessType() : null; }
    public String getBusinessSubType() { return isTenantInitialized() ? tenant.getBusinessSubType() : null; }
    public String getAddress() { return isTenantInitialized() ? tenant.getAddress() : null; }
    public String getAboutUs() { return isTenantInitialized() ? tenant.getAboutUs() : null; }
    public Double getLatitude() { return isTenantInitialized() ? tenant.getLatitude() : null; }
    public Double getLongitude() { return isTenantInitialized() ? tenant.getLongitude() : null; }
    public String getLogoUrl() { return isTenantInitialized() ? tenant.getLogoUrl() : null; }
    public Boolean getOnboardingCompleted() { return isTenantInitialized() ? tenant.getOnboardingCompleted() : false; }
    public PlanType getPlanType() { return isTenantInitialized() && tenant.getPlanType() != null ? PlanType.valueOf(tenant.getPlanType().name()) : PlanType.FREE; }

    // ── Backward Compatibility Delegation Setters ──
    public void setBusinessName(String name) { if (isTenantInitialized()) tenant.setBusinessName(name); }
    public void setBusinessType(String type) { if (isTenantInitialized()) tenant.setBusinessType(type); }
    public void setBusinessSubType(String subType) { if (isTenantInitialized()) tenant.setBusinessSubType(subType); }
    public void setAddress(String addr) { if (isTenantInitialized()) tenant.setAddress(addr); }
    public void setAboutUs(String about) { if (isTenantInitialized()) tenant.setAboutUs(about); }
    public void setLatitude(Double lat) { if (isTenantInitialized()) tenant.setLatitude(lat); }
    public void setLongitude(Double lon) { if (isTenantInitialized()) tenant.setLongitude(lon); }
    public void setLogoUrl(String url) { if (isTenantInitialized()) tenant.setLogoUrl(url); }
    public void setOnboardingCompleted(Boolean comp) { if (isTenantInitialized()) tenant.setOnboardingCompleted(comp); }
    public void setPlanType(PlanType plan) { if (isTenantInitialized()) tenant.setPlanType(com.chatcrmlite.backend.models.User.PlanType.valueOf(plan.name())); }
    public void setForceShowBooking(Boolean val) { if (isTenantInitialized()) tenant.setForceShowBooking(val); }
    public void setForceShowAppointment(Boolean val) { if (isTenantInitialized()) tenant.setForceShowAppointment(val); }
    public void setForceShowLeads(Boolean val) { if (isTenantInitialized()) tenant.setForceShowLeads(val); }

    public Boolean getForceShowBooking() { return isTenantInitialized() ? tenant.getForceShowBooking() : null; }
    public Boolean getForceShowAppointment() { return isTenantInitialized() ? tenant.getForceShowAppointment() : null; }
    public Boolean getForceShowLeads() { return isTenantInitialized() ? tenant.getForceShowLeads() : null; }

    public LocalDateTime getConsentAt() { return consentAt; }
    public void setConsentAt(LocalDateTime consentAt) { this.consentAt = consentAt; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getFirstName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.split(" ")[0];
        }
        return email != null ? email.split("@")[0] : "User";
    }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public List<String> getPermissions() { return permissions != null ? permissions : new ArrayList<>(); }
    public void setPermissions(List<String> permissions) { this.permissions = permissions != null ? permissions : new ArrayList<>(); }

    public Integer getPermissionVersion() { return permissionVersion != null ? permissionVersion : 1; }
    public void setPermissionVersion(Integer permissionVersion) { this.permissionVersion = permissionVersion; }

    public AccountStatus getAccountStatus() { return accountStatus; }
    public void setAccountStatus(AccountStatus accountStatus) { this.accountStatus = accountStatus; }

    public AvailabilityStatus getAvailabilityStatus() { return availabilityStatus != null ? availabilityStatus : AvailabilityStatus.AVAILABLE; }
    public void setAvailabilityStatus(AvailabilityStatus availabilityStatus) { this.availabilityStatus = availabilityStatus; }

    public Integer getMaxConcurrentChats() { return maxConcurrentChats != null ? maxConcurrentChats : 2; }
    public void setMaxConcurrentChats(Integer maxConcurrentChats) { this.maxConcurrentChats = maxConcurrentChats; }

    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public Set<String> getIpWhitelist() { return ipWhitelist; }
    public void setIpWhitelist(Set<String> ipWhitelist) { this.ipWhitelist = ipWhitelist; }

    public Boolean getBiometricsEnabled() { return biometricsEnabled; }
    public void setBiometricsEnabled(Boolean biometricsEnabled) { this.biometricsEnabled = biometricsEnabled; }

    public Boolean getLoginAlertsEnabled() { return loginAlertsEnabled; }
    public void setLoginAlertsEnabled(Boolean loginAlertsEnabled) { this.loginAlertsEnabled = loginAlertsEnabled; }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public WhatsAppConfig getWhatsappConfig() {
        if (whatsappConfig != null) return whatsappConfig;
        return (tenant != null && org.hibernate.Hibernate.isInitialized(tenant)) ? tenant.getWhatsappConfig() : null;
    }
    public void setWhatsappConfig(WhatsAppConfig whatsappConfig) {
        this.whatsappConfig = whatsappConfig;
        if (whatsappConfig != null && tenant != null) {
            tenant.setWhatsappConfig(whatsappConfig);
            whatsappConfig.setTenant(tenant);
        }
    }

    public String getGoogleAccessToken() { return googleAccessToken; }
    public void setGoogleAccessToken(String googleAccessToken) { this.googleAccessToken = googleAccessToken; }

    public String getGoogleRefreshToken() { return googleRefreshToken; }
    public void setGoogleRefreshToken(String googleRefreshToken) { this.googleRefreshToken = googleRefreshToken; }

    public LocalDateTime getGoogleTokenExpiry() { return googleTokenExpiry; }
    public void setGoogleTokenExpiry(LocalDateTime googleTokenExpiry) { this.googleTokenExpiry = googleTokenExpiry; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (role == null) {
            role = Role.OWNER;
        }
        if (tenant == null && role == Role.OWNER) {
            // Default tenant setup for standalone owner sign up
            tenant = Tenant.builder()
                    .businessName("My Business")
                    .build();
        }
    }

    public enum AccountStatus {
        ACTIVE,
        LOCKED,
        SUSPENDED,
        DEACTIVATED
    }

    public enum Role {
        OWNER, ADMIN, AGENT, SUPER_ADMIN
    }

    public enum PlanType {
        FREE, PRO, ENTERPRISE
    }

    public static UserBuilder builder() { return new UserBuilder(); }

    public static class UserBuilder {
        private UUID id;
        private String email;
        private String password;
        private Tenant tenant;
        private LocalDateTime consentAt;
        private String displayName;
        private String phone;
        private Role role;
        private AccountStatus accountStatus = AccountStatus.ACTIVE;
        private Set<String> ipWhitelist;
        private Boolean biometricsEnabled = false;
        private Boolean loginAlertsEnabled = false;
        private WhatsAppConfig whatsappConfig;
        private String googleAccessToken;
        private String googleRefreshToken;
        private LocalDateTime googleTokenExpiry;
        private LocalDateTime createdAt;

        // Support backward compatible builder fields
        private String businessName;
        private String businessType;
        private String businessSubType;
        private PlanType planType = PlanType.FREE;
        private Boolean onboardingCompleted = false;

        public UserBuilder id(UUID id) { this.id = id; return this; }
        public UserBuilder email(String email) { this.email = email; return this; }
        public UserBuilder password(String password) { this.password = password; return this; }
        public UserBuilder tenant(Tenant tenant) { this.tenant = tenant; return this; }
        public UserBuilder consentAt(LocalDateTime consentAt) { this.consentAt = consentAt; return this; }
        public UserBuilder displayName(String displayName) { this.displayName = displayName; return this; }
        public UserBuilder phone(String phone) { this.phone = phone; return this; }
        public UserBuilder role(Role role) { this.role = role; return this; }
        public UserBuilder accountStatus(AccountStatus accountStatus) { this.accountStatus = accountStatus; return this; }
        public UserBuilder ipWhitelist(Set<String> ipWhitelist) { this.ipWhitelist = ipWhitelist; return this; }
        public UserBuilder biometricsEnabled(Boolean biometricsEnabled) { this.biometricsEnabled = biometricsEnabled; return this; }
        public UserBuilder loginAlertsEnabled(Boolean loginAlertsEnabled) { this.loginAlertsEnabled = loginAlertsEnabled; return this; }
        public UserBuilder whatsappConfig(WhatsAppConfig whatsappConfig) { this.whatsappConfig = whatsappConfig; return this; }
        public UserBuilder googleAccessToken(String googleAccessToken) { this.googleAccessToken = googleAccessToken; return this; }
        public UserBuilder googleRefreshToken(String googleRefreshToken) { this.googleRefreshToken = googleRefreshToken; return this; }
        public UserBuilder googleTokenExpiry(LocalDateTime googleTokenExpiry) { this.googleTokenExpiry = googleTokenExpiry; return this; }
        public UserBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public UserBuilder businessName(String businessName) { this.businessName = businessName; return this; }
        public UserBuilder businessType(String businessType) { this.businessType = businessType; return this; }
        public UserBuilder businessSubType(String businessSubType) { this.businessSubType = businessSubType; return this; }
        public UserBuilder planType(PlanType planType) { this.planType = planType; return this; }
        public UserBuilder onboardingCompleted(Boolean onboardingCompleted) { this.onboardingCompleted = onboardingCompleted; return this; }

        public User build() {
            Tenant builtTenant = tenant;
            if (builtTenant == null && (businessName != null || planType != null || businessType != null || businessSubType != null)) {
                builtTenant = Tenant.builder()
                        .businessName(businessName != null ? businessName : "My Business")
                        .businessType(businessType)
                        .businessSubType(businessSubType)
                        .planType(planType != null ? com.chatcrmlite.backend.models.User.PlanType.valueOf(planType.name()) : com.chatcrmlite.backend.models.User.PlanType.FREE)
                        .onboardingCompleted(onboardingCompleted != null && onboardingCompleted)
                        .build();
            }
            return new User(id, email, password, builtTenant, consentAt, displayName, phone, role, accountStatus, ipWhitelist, biometricsEnabled, loginAlertsEnabled, whatsappConfig, googleAccessToken, googleRefreshToken, googleTokenExpiry, createdAt);
        }
    }
}
