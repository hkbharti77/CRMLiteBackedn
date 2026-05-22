package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_configs")
public class WhatsAppConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Transient
    @JsonIgnore
    private User user;

    @Column(unique = true, nullable = false)
    private String phoneNumberId;

    private String wabaId;

    @Column(length = 1000)
    private String accessToken;

    private String verifyToken;

    @Column(length = 255)
    private String appSecret;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "interactive_menu_json", columnDefinition = "jsonb")
    private String interactiveMenuJson;

    @Column(columnDefinition = "TEXT")
    private String welcomeMessage;

    @Column(columnDefinition = "TEXT")
    private String returningMessage;

    @Column(length = 500)
    private String reviewUrl;

    @Column(length = 500)
    private String portfolioUrl;

    @Column(columnDefinition = "TEXT")
    private String offerText;

    @Column(length = 255)
    private String sosNote;

    @Column(length = 50)
    private String thirdButtonType; // TRUST, SOCIAL, OFFER, NONE

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_sub_menus_json", columnDefinition = "jsonb")
    private String customSubMenusJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_messages_json", columnDefinition = "jsonb")
    private String customMessagesJson;

    private Boolean showAboutContact = true;
    private Boolean showTrustButton = true;
    private Boolean showOfferButton = true;
    private Boolean showSosButton = true;

    public WhatsAppConfig() {}

    public WhatsAppConfig(UUID id, Tenant tenant, String phoneNumberId, String wabaId, String accessToken, String verifyToken, String appSecret, String interactiveMenuJson, String welcomeMessage, String returningMessage, String reviewUrl, String portfolioUrl, String offerText, String sosNote, String thirdButtonType, String customSubMenusJson, String customMessagesJson, Boolean showAboutContact, Boolean showTrustButton, Boolean showOfferButton, Boolean showSosButton) {
        this.id = id;
        this.tenant = tenant;
        this.phoneNumberId = phoneNumberId;
        this.wabaId = wabaId;
        this.accessToken = accessToken;
        this.verifyToken = verifyToken;
        this.appSecret = appSecret;
        this.interactiveMenuJson = interactiveMenuJson;
        this.welcomeMessage = welcomeMessage;
        this.returningMessage = returningMessage;
        this.reviewUrl = reviewUrl;
        this.portfolioUrl = portfolioUrl;
        this.offerText = offerText;
        this.sosNote = sosNote;
        this.thirdButtonType = thirdButtonType;
        this.customSubMenusJson = customSubMenusJson;
        this.customMessagesJson = customMessagesJson;
        this.showAboutContact = showAboutContact;
        this.showTrustButton = showTrustButton;
        this.showOfferButton = showOfferButton;
        this.showSosButton = showSosButton;
    }

    public UUID getId() { return id; }
    public Tenant getTenant() { return tenant; }
    public String getPhoneNumberId() { return phoneNumberId; }
    public String getWabaId() { return wabaId; }
    public String getAccessToken() { return accessToken; }
    public String getVerifyToken() { return verifyToken; }
    public String getAppSecret() { return appSecret; }
    public String getInteractiveMenuJson() { return interactiveMenuJson; }
    public String getWelcomeMessage() { return welcomeMessage; }
    public String getReturningMessage() { return returningMessage; }
    public String getReviewUrl() { return reviewUrl; }
    public String getPortfolioUrl() { return portfolioUrl; }
    public String getOfferText() { return offerText; }
    public String getSosNote() { return sosNote; }
    public String getThirdButtonType() { return thirdButtonType; }
    public String getCustomSubMenusJson() { return customSubMenusJson; }
    public String getCustomMessagesJson() { return customMessagesJson; }
    public Boolean getShowAboutContact() { return showAboutContact; }
    public Boolean getShowTrustButton() { return showTrustButton; }
    public Boolean getShowOfferButton() { return showOfferButton; }
    public Boolean getShowSosButton() { return showSosButton; }

    // ── Transient User Helpers for Backward Compatibility ──
    public User getUser() {
        if (user != null) return user;
        // FIX #8: Safely handle lazy-loaded tenant.getUsers() with proper null checks
        if (tenant != null) {
            try {
                java.util.Set<User> users = tenant.getUsers();
                if (users != null && !users.isEmpty()) {
                    return users.stream()
                            .filter(u -> u.getRole() == User.Role.OWNER)
                            .findFirst()
                            .orElseGet(() -> users.stream().findFirst().orElse(null));
                }
            } catch (org.hibernate.LazyInitializationException e) {
                // FIX #8: Log lazy initialization error instead of crashing
                org.slf4j.LoggerFactory.getLogger(WhatsAppConfig.class)
                    .warn("[WhatsAppConfig] LazyInitializationException accessing tenant.getUsers() for config id={}: {}", 
                        this.id, e.getMessage());
                return null;
            }
        }
        return null;
    }

    public void setUser(User user) {
        this.user = user;
        if (user != null && this.tenant == null) {
            this.tenant = user.getTenant();
        }
    }

    public void setId(UUID id) { this.id = id; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    public void setPhoneNumberId(String phoneNumberId) { this.phoneNumberId = phoneNumberId; }
    public void setWabaId(String wabaId) { this.wabaId = wabaId; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public void setVerifyToken(String verifyToken) { this.verifyToken = verifyToken; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
    public void setInteractiveMenuJson(String interactiveMenuJson) { this.interactiveMenuJson = interactiveMenuJson; }
    public void setWelcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; }
    public void setReturningMessage(String returningMessage) { this.returningMessage = returningMessage; }
    public void setReviewUrl(String reviewUrl) { this.reviewUrl = reviewUrl; }
    public void setPortfolioUrl(String portfolioUrl) { this.portfolioUrl = portfolioUrl; }
    public void setOfferText(String offerText) { this.offerText = offerText; }
    public void setSosNote(String sosNote) { this.sosNote = sosNote; }
    public void setThirdButtonType(String thirdButtonType) { this.thirdButtonType = thirdButtonType; }
    public void setCustomSubMenusJson(String customSubMenusJson) { this.customSubMenusJson = customSubMenusJson; }
    public void setCustomMessagesJson(String customMessagesJson) { this.customMessagesJson = customMessagesJson; }
    public void setShowAboutContact(Boolean showAboutContact) { this.showAboutContact = showAboutContact; }
    public void setShowTrustButton(Boolean showTrustButton) { this.showTrustButton = showTrustButton; }
    public void setShowOfferButton(Boolean showOfferButton) { this.showOfferButton = showOfferButton; }
    public void setShowSosButton(Boolean showSosButton) { this.showSosButton = showSosButton; }

    public static WhatsAppConfigBuilder builder() { return new WhatsAppConfigBuilder(); }

    public static class WhatsAppConfigBuilder {
        private UUID id;
        private Tenant tenant;
        private User user;
        private String phoneNumberId;
        private String wabaId;
        private String accessToken;
        private String verifyToken;
        private String appSecret;
        private String interactiveMenuJson;
        private String welcomeMessage;
        private String returningMessage;
        private String reviewUrl;
        private String portfolioUrl;
        private String offerText;
        private String sosNote;
        private String thirdButtonType;
        private String customSubMenusJson;
        private String customMessagesJson;
        private Boolean showAboutContact = true;
        private Boolean showTrustButton = true;
        private Boolean showOfferButton = true;
        private Boolean showSosButton = true;

        public WhatsAppConfigBuilder id(UUID id) { this.id = id; return this; }
        public WhatsAppConfigBuilder tenant(Tenant tenant) { this.tenant = tenant; return this; }
        public WhatsAppConfigBuilder user(User user) { 
            this.user = user; 
            if (user != null) {
                this.tenant = user.getTenant();
            }
            return this; 
        }
        public WhatsAppConfigBuilder phoneNumberId(String phoneNumberId) { this.phoneNumberId = phoneNumberId; return this; }
        public WhatsAppConfigBuilder wabaId(String wabaId) { this.wabaId = wabaId; return this; }
        public WhatsAppConfigBuilder accessToken(String accessToken) { this.accessToken = accessToken; return this; }
        public WhatsAppConfigBuilder verifyToken(String verifyToken) { this.verifyToken = verifyToken; return this; }
        public WhatsAppConfigBuilder appSecret(String appSecret) { this.appSecret = appSecret; return this; }
        public WhatsAppConfigBuilder interactiveMenuJson(String interactiveMenuJson) { this.interactiveMenuJson = interactiveMenuJson; return this; }
        public WhatsAppConfigBuilder welcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; return this; }
        public WhatsAppConfigBuilder returningMessage(String returningMessage) { this.returningMessage = returningMessage; return this; }
        public WhatsAppConfigBuilder reviewUrl(String reviewUrl) { this.reviewUrl = reviewUrl; return this; }
        public WhatsAppConfigBuilder portfolioUrl(String portfolioUrl) { this.portfolioUrl = portfolioUrl; return this; }
        public WhatsAppConfigBuilder offerText(String offerText) { this.offerText = offerText; return this; }
        public WhatsAppConfigBuilder sosNote(String sosNote) { this.sosNote = sosNote; return this; }
        public WhatsAppConfigBuilder thirdButtonType(String thirdButtonType) { this.thirdButtonType = thirdButtonType; return this; }
        public WhatsAppConfigBuilder customSubMenusJson(String customSubMenusJson) { this.customSubMenusJson = customSubMenusJson; return this; }
        public WhatsAppConfigBuilder customMessagesJson(String customMessagesJson) { this.customMessagesJson = customMessagesJson; return this; }
        public WhatsAppConfigBuilder showAboutContact(Boolean showAboutContact) { this.showAboutContact = showAboutContact; return this; }
        public WhatsAppConfigBuilder showTrustButton(Boolean showTrustButton) { this.showTrustButton = showTrustButton; return this; }
        public WhatsAppConfigBuilder showOfferButton(Boolean showOfferButton) { this.showOfferButton = showOfferButton; return this; }
        public WhatsAppConfigBuilder showSosButton(Boolean showSosButton) { this.showSosButton = showSosButton; return this; }

        public WhatsAppConfig build() {
            WhatsAppConfig config = new WhatsAppConfig(id, tenant, phoneNumberId, wabaId, accessToken, verifyToken, appSecret, interactiveMenuJson, welcomeMessage, returningMessage, reviewUrl, portfolioUrl, offerText, sosNote, thirdButtonType, customSubMenusJson, customMessagesJson, showAboutContact, showTrustButton, showOfferButton, showSosButton);
            if (user != null) {
                config.setUser(user);
            }
            return config;
        }
    }
}
