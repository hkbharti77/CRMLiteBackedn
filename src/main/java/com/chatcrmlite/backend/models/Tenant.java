package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "tenants")
public class Tenant implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String businessName;

    private String businessType;
    private String businessSubType;
    private String address;

    @Column(columnDefinition = "TEXT")
    private String aboutUs;

    private Double latitude;
    private Double longitude;
    private String logoUrl;

    @Column(name = "primary_color", length = 20)
    private String primaryColor;

    @Column(name = "secondary_color", length = 20)
    private String secondaryColor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private User.PlanType planType = User.PlanType.FREE;

    @Column(nullable = false)
    private Boolean onboardingCompleted = false;

    private Boolean forceShowBooking = null;
    private Boolean forceShowAppointment = null;
    private Boolean forceShowLeads = null;

    public enum PrimaryResource {
        LEAD,
        APPOINTMENT,
        BOOKING
    }

    @Column(name = "country", length = 10)
    private String country = "IN";

    @Column(name = "currency", length = 10)
    private String currency = "INR";

    @Column(name = "timezone", length = 50)
    private String timezone = "Asia/Kolkata";

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_resource", nullable = false)
    private PrimaryResource primaryResource = PrimaryResource.LEAD;

    // ── Platform Owner Lifecycle Management ───────────────────────────────────
    public enum LifecycleStatus {
        ACTIVE, SUSPENDED, LOCKED, ARCHIVED, DELETED
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 20)
    private LifecycleStatus lifecycleStatus = LifecycleStatus.ACTIVE;

    @Column(name = "suspension_reason", columnDefinition = "TEXT")
    private String suspensionReason;

    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToOne(mappedBy = "tenant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private WhatsAppConfig whatsappConfig;

    @JsonIgnore
    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<User> users = new HashSet<>();

    public Tenant() {}

    public Tenant(UUID id, String businessName, String businessType, String businessSubType, String address, String aboutUs, Double latitude, Double longitude, String logoUrl, User.PlanType planType, Boolean onboardingCompleted, Boolean forceShowBooking, Boolean forceShowAppointment, Boolean forceShowLeads, PrimaryResource primaryResource, LocalDateTime createdAt, WhatsAppConfig whatsappConfig, Set<User> users) {
        this.id = id;
        this.businessName = businessName;
        this.businessType = businessType;
        this.businessSubType = businessSubType;
        this.address = address;
        this.aboutUs = aboutUs;
        this.latitude = latitude;
        this.longitude = longitude;
        this.logoUrl = logoUrl;
        this.planType = planType != null ? planType : User.PlanType.FREE;
        this.onboardingCompleted = onboardingCompleted != null ? onboardingCompleted : false;
        this.forceShowBooking = forceShowBooking;
        this.forceShowAppointment = forceShowAppointment;
        this.forceShowLeads = forceShowLeads;
        this.primaryResource = primaryResource != null ? primaryResource : PrimaryResource.LEAD;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.whatsappConfig = whatsappConfig;
        this.users = users != null ? users : new HashSet<>();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

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

    public User.PlanType getPlanType() { return planType; }
    public void setPlanType(User.PlanType planType) { this.planType = planType; }

    public Boolean getOnboardingCompleted() { return onboardingCompleted; }
    public void setOnboardingCompleted(Boolean onboardingCompleted) { this.onboardingCompleted = onboardingCompleted; }

    public Boolean getForceShowBooking() { return forceShowBooking; }
    public void setForceShowBooking(Boolean forceShowBooking) { this.forceShowBooking = forceShowBooking; }

    public Boolean getForceShowAppointment() { return forceShowAppointment; }
    public void setForceShowAppointment(Boolean forceShowAppointment) { this.forceShowAppointment = forceShowAppointment; }

    public Boolean getForceShowLeads() { return forceShowLeads; }
    public void setForceShowLeads(Boolean forceShowLeads) { this.forceShowLeads = forceShowLeads; }

    public String getCountry() { return country != null ? country : "IN"; }
    public void setCountry(String country) { this.country = country; }

    public String getCurrency() { return currency != null ? currency : "INR"; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getTimezone() { return timezone != null ? timezone : "Asia/Kolkata"; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public PrimaryResource getPrimaryResource() { return primaryResource; }
    public void setPrimaryResource(PrimaryResource primaryResource) { this.primaryResource = primaryResource; }

    public LifecycleStatus getLifecycleStatus() { return lifecycleStatus; }
    public void setLifecycleStatus(LifecycleStatus lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }

    public String getSuspensionReason() { return suspensionReason; }
    public void setSuspensionReason(String suspensionReason) { this.suspensionReason = suspensionReason; }

    public LocalDateTime getSuspendedAt() { return suspendedAt; }
    public void setSuspendedAt(LocalDateTime suspendedAt) { this.suspendedAt = suspendedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public WhatsAppConfig getWhatsappConfig() { return whatsappConfig; }
    public void setWhatsappConfig(WhatsAppConfig whatsappConfig) { this.whatsappConfig = whatsappConfig; }

    public Set<User> getUsers() { return users; }
    public void setUsers(Set<User> users) { this.users = users; }

    public static TenantBuilder builder() { return new TenantBuilder(); }

    public static class TenantBuilder {
        private UUID id;
        private String businessName;
        private String businessType;
        private String businessSubType;
        private String address;
        private String aboutUs;
        private Double latitude;
        private Double longitude;
        private String logoUrl;
        private User.PlanType planType = User.PlanType.FREE;
        private Boolean onboardingCompleted = false;
        private Boolean forceShowBooking;
        private Boolean forceShowAppointment;
        private Boolean forceShowLeads;
        private PrimaryResource primaryResource = PrimaryResource.LEAD;
        private LocalDateTime createdAt;
        private WhatsAppConfig whatsappConfig;
        private Set<User> users;

        public TenantBuilder id(UUID id) { this.id = id; return this; }
        public TenantBuilder businessName(String businessName) { this.businessName = businessName; return this; }
        public TenantBuilder businessType(String businessType) { this.businessType = businessType; return this; }
        public TenantBuilder businessSubType(String businessSubType) { this.businessSubType = businessSubType; return this; }
        public TenantBuilder address(String address) { this.address = address; return this; }
        public TenantBuilder aboutUs(String aboutUs) { this.aboutUs = aboutUs; return this; }
        public TenantBuilder latitude(Double latitude) { this.latitude = latitude; return this; }
        public TenantBuilder longitude(Double longitude) { this.longitude = longitude; return this; }
        public TenantBuilder logoUrl(String logoUrl) { this.logoUrl = logoUrl; return this; }
        public TenantBuilder planType(User.PlanType planType) { this.planType = planType; return this; }
        public TenantBuilder onboardingCompleted(Boolean onboardingCompleted) { this.onboardingCompleted = onboardingCompleted; return this; }
        public TenantBuilder forceShowBooking(Boolean forceShowBooking) { this.forceShowBooking = forceShowBooking; return this; }
        public TenantBuilder forceShowAppointment(Boolean forceShowAppointment) { this.forceShowAppointment = forceShowAppointment; return this; }
        public TenantBuilder forceShowLeads(Boolean forceShowLeads) { this.forceShowLeads = forceShowLeads; return this; }
        public TenantBuilder primaryResource(PrimaryResource primaryResource) { this.primaryResource = primaryResource; return this; }
        public TenantBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public TenantBuilder whatsappConfig(WhatsAppConfig whatsappConfig) { this.whatsappConfig = whatsappConfig; return this; }
        public TenantBuilder users(Set<User> users) { this.users = users; return this; }

        public Tenant build() {
            return new Tenant(id, businessName, businessType, businessSubType, address, aboutUs, latitude, longitude, logoUrl, planType, onboardingCompleted, forceShowBooking, forceShowAppointment, forceShowLeads, primaryResource, createdAt, whatsappConfig, users);
        }
    }
}
