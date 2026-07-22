package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "app_users", indexes = {
    @Index(name = "idx_user_email", columnList = "email")
})
public class User implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    private AccountStatus accountStatus = AccountStatus.ACTIVE;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_ip_whitelist", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "ip_address")
    private Set<String> ipWhitelist = new HashSet<>();

    private Boolean biometricsEnabled = false;
    private Boolean loginAlertsEnabled = false;

    @Transient
    private WhatsAppConfig whatsappConfig;

    private String googleAccessToken;
    private String googleRefreshToken;
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

    // ── Backward Compatibility Delegation Getters ──
    public String getBusinessName() { return tenant != null ? tenant.getBusinessName() : null; }
    public String getBusinessType() { return tenant != null ? tenant.getBusinessType() : null; }
    public String getBusinessSubType() { return tenant != null ? tenant.getBusinessSubType() : null; }
    public String getAddress() { return tenant != null ? tenant.getAddress() : null; }
    public String getAboutUs() { return tenant != null ? tenant.getAboutUs() : null; }
    public Double getLatitude() { return tenant != null ? tenant.getLatitude() : null; }
    public Double getLongitude() { return tenant != null ? tenant.getLongitude() : null; }
    public String getLogoUrl() { return tenant != null ? tenant.getLogoUrl() : null; }
    public Boolean getOnboardingCompleted() { return tenant != null ? tenant.getOnboardingCompleted() : false; }
    public PlanType getPlanType() { return tenant != null ? PlanType.valueOf(tenant.getPlanType().name()) : PlanType.FREE; }

    // ── Backward Compatibility Delegation Setters ──
    public void setBusinessName(String name) { if (tenant != null) tenant.setBusinessName(name); }
    public void setBusinessType(String type) { if (tenant != null) tenant.setBusinessType(type); }
    public void setBusinessSubType(String subType) { if (tenant != null) tenant.setBusinessSubType(subType); }
    public void setAddress(String addr) { if (tenant != null) tenant.setAddress(addr); }
    public void setAboutUs(String about) { if (tenant != null) tenant.setAboutUs(about); }
    public void setLatitude(Double lat) { if (tenant != null) tenant.setLatitude(lat); }
    public void setLongitude(Double lon) { if (tenant != null) tenant.setLongitude(lon); }
    public void setLogoUrl(String url) { if (tenant != null) tenant.setLogoUrl(url); }
    public void setOnboardingCompleted(Boolean comp) { if (tenant != null) tenant.setOnboardingCompleted(comp); }
    public void setPlanType(PlanType plan) { if (tenant != null) tenant.setPlanType(com.chatcrmlite.backend.models.User.PlanType.valueOf(plan.name())); }
    public void setForceShowBooking(Boolean val) { if (tenant != null) tenant.setForceShowBooking(val); }
    public void setForceShowAppointment(Boolean val) { if (tenant != null) tenant.setForceShowAppointment(val); }
    public void setForceShowLeads(Boolean val) { if (tenant != null) tenant.setForceShowLeads(val); }

    public Boolean getForceShowBooking() { return tenant != null ? tenant.getForceShowBooking() : null; }
    public Boolean getForceShowAppointment() { return tenant != null ? tenant.getForceShowAppointment() : null; }
    public Boolean getForceShowLeads() { return tenant != null ? tenant.getForceShowLeads() : null; }

    public LocalDateTime getConsentAt() { return consentAt; }
    public void setConsentAt(LocalDateTime consentAt) { this.consentAt = consentAt; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public AccountStatus getAccountStatus() { return accountStatus; }
    public void setAccountStatus(AccountStatus accountStatus) { this.accountStatus = accountStatus; }

    public Set<String> getIpWhitelist() { return ipWhitelist; }
    public void setIpWhitelist(Set<String> ipWhitelist) { this.ipWhitelist = ipWhitelist; }

    public Boolean getBiometricsEnabled() { return biometricsEnabled; }
    public void setBiometricsEnabled(Boolean biometricsEnabled) { this.biometricsEnabled = biometricsEnabled; }

    public Boolean getLoginAlertsEnabled() { return loginAlertsEnabled; }
    public void setLoginAlertsEnabled(Boolean loginAlertsEnabled) { this.loginAlertsEnabled = loginAlertsEnabled; }

    public WhatsAppConfig getWhatsappConfig() {
        if (whatsappConfig != null) return whatsappConfig;
        return tenant != null ? tenant.getWhatsappConfig() : null;
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
        OWNER, ADMIN, AGENT
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
