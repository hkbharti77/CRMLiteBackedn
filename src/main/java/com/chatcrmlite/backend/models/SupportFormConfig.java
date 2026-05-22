package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "support_form_configs")
public class SupportFormConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne
    @JoinColumn(name = "owner_id", nullable = false, unique = true)
    private User owner;

    @Column(name = "form_title")
    private String formTitle = "Get Support";

    @Column(name = "form_description")
    private String formDescription = "Need help? Submit your request and we'll get back to you soon.";

    @Column(name = "success_message")
    private String successMessage = "✅ Thank you for contacting support! We've received your request and will get back to you shortly.";

    private String categories = "Technical,Billing,General,Bug Report,Feature Request";

    private boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public SupportFormConfig() {}

    public SupportFormConfig(UUID id, User owner, String formTitle, String formDescription, String successMessage, String categories, boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.owner = owner;
        this.formTitle = formTitle;
        this.formDescription = formDescription;
        this.successMessage = successMessage;
        this.categories = categories;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public User getOwner() { return owner; }
    public String getFormTitle() { return formTitle; }
    public String getFormDescription() { return formDescription; }
    public String getSuccessMessage() { return successMessage; }
    public String getCategories() { return categories; }
    public boolean isEnabled() { return enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(UUID id) { this.id = id; }
    public void setOwner(User owner) { this.owner = owner; }
    public void setFormTitle(String formTitle) { this.formTitle = formTitle; }
    public void setFormDescription(String formDescription) { this.formDescription = formDescription; }
    public void setSuccessMessage(String successMessage) { this.successMessage = successMessage; }
    public void setCategories(String categories) { this.categories = categories; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Persisted Config Fields ───────────────────────────────────────────
    // FIXED AP-4: these were @Transient (never persisted). Now real columns.

    @Column(name = "phone_required", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean phoneRequired = false;

    @Column(name = "category_required", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean categoryRequired = false;

    @Column(name = "rate_limit_enabled", nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    private boolean rateLimitEnabled = true;

    @Column(name = "duplicate_detection_enabled", nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    private boolean duplicateDetectionEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_priority", length = 20, columnDefinition = "VARCHAR(20) DEFAULT 'MEDIUM'")
    private Ticket.TicketPriority defaultPriority = Ticket.TicketPriority.MEDIUM;

    @Column(name = "primary_color", length = 20)
    private String primaryColor = "#667eea";

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    public boolean isPhoneRequired() { return phoneRequired; }
    public void setPhoneRequired(boolean phoneRequired) { this.phoneRequired = phoneRequired; }
    public boolean isCategoryRequired() { return categoryRequired; }
    public void setCategoryRequired(boolean categoryRequired) { this.categoryRequired = categoryRequired; }
    public boolean isRateLimitEnabled() { return rateLimitEnabled; }
    public void setRateLimitEnabled(boolean rateLimitEnabled) { this.rateLimitEnabled = rateLimitEnabled; }
    public boolean isDuplicateDetectionEnabled() { return duplicateDetectionEnabled; }
    public void setDuplicateDetectionEnabled(boolean duplicateDetectionEnabled) { this.duplicateDetectionEnabled = duplicateDetectionEnabled; }
    public Ticket.TicketPriority getDefaultPriority() { return defaultPriority; }
    public void setDefaultPriority(Ticket.TicketPriority defaultPriority) { this.defaultPriority = defaultPriority; }
    public String getPrimaryColor() { return primaryColor; }
    public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public static SupportFormConfigBuilder builder() { return new SupportFormConfigBuilder(); }

    public static class SupportFormConfigBuilder {
        private UUID id;
        private User owner;
        private String formTitle = "Get Support";
        private String formDescription = "Need help? Submit your request and we'll get back to you soon.";
        private String successMessage = "✅ Thank you for contacting support! We've received your request and will get back to you shortly.";
        private String categories = "Technical,Billing,General,Bug Report,Feature Request";
        private boolean enabled = true;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt;

        // Transient fields in builder
        private boolean phoneRequired = false;
        private boolean categoryRequired = false;
        private boolean rateLimitEnabled = true;
        private boolean duplicateDetectionEnabled = true;
        private Ticket.TicketPriority defaultPriority = Ticket.TicketPriority.MEDIUM;
        private String primaryColor = "#667eea";
        private String logoUrl;

        public SupportFormConfigBuilder id(UUID id) { this.id = id; return this; }
        public SupportFormConfigBuilder owner(User owner) { this.owner = owner; return this; }
        public SupportFormConfigBuilder formTitle(String formTitle) { this.formTitle = formTitle; return this; }
        public SupportFormConfigBuilder formDescription(String formDescription) { this.formDescription = formDescription; return this; }
        public SupportFormConfigBuilder successMessage(String successMessage) { this.successMessage = successMessage; return this; }
        public SupportFormConfigBuilder categories(String categories) { this.categories = categories; return this; }
        public SupportFormConfigBuilder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public SupportFormConfigBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public SupportFormConfigBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public SupportFormConfigBuilder phoneRequired(boolean phoneRequired) { this.phoneRequired = phoneRequired; return this; }
        public SupportFormConfigBuilder categoryRequired(boolean categoryRequired) { this.categoryRequired = categoryRequired; return this; }
        public SupportFormConfigBuilder rateLimitEnabled(boolean rateLimitEnabled) { this.rateLimitEnabled = rateLimitEnabled; return this; }
        public SupportFormConfigBuilder duplicateDetectionEnabled(boolean duplicateDetectionEnabled) { this.duplicateDetectionEnabled = duplicateDetectionEnabled; return this; }
        public SupportFormConfigBuilder defaultPriority(Ticket.TicketPriority defaultPriority) { this.defaultPriority = defaultPriority; return this; }
        public SupportFormConfigBuilder primaryColor(String primaryColor) { this.primaryColor = primaryColor; return this; }
        public SupportFormConfigBuilder logoUrl(String logoUrl) { this.logoUrl = logoUrl; return this; }

        public SupportFormConfig build() {
            SupportFormConfig config = new SupportFormConfig(id, owner, formTitle, formDescription, successMessage, categories, enabled, createdAt, updatedAt);
            config.setPhoneRequired(phoneRequired);
            config.setCategoryRequired(categoryRequired);
            config.setRateLimitEnabled(rateLimitEnabled);
            config.setDuplicateDetectionEnabled(duplicateDetectionEnabled);
            config.setDefaultPriority(defaultPriority);
            config.setPrimaryColor(primaryColor);
            config.setLogoUrl(logoUrl);
            return config;
        }
    }
}
