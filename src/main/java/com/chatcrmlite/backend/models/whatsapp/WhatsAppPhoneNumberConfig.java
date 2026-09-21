package com.chatcrmlite.backend.models.whatsapp;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_phone_number_configs", uniqueConstraints = {
    @UniqueConstraint(name = "uq_phone_number_config", columnNames = {"tenant_id", "waba_id", "phone_number_id"})
}, indexes = {
    @Index(name = "idx_phone_cfg_lookup", columnList = "tenant_id, phone_number_id"),
    @Index(name = "idx_phone_cfg_waba", columnList = "tenant_id, waba_id, phone_number_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WhatsAppPhoneNumberConfig extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "waba_id", length = 100)
    private String wabaId;

    @Column(name = "phone_number_id", nullable = false, length = 100)
    private String phoneNumberId;

    @Column(name = "display_phone_number", length = 50)
    private String displayPhoneNumber;

    @Column(name = "calling_status", length = 50)
    private String callingStatus;

    @Column(name = "call_icon_visibility", length = 50)
    private String callIconVisibility;

    @Column(name = "callback_permission_status", length = 50)
    private String callbackPermissionStatus;

    @Column(name = "sip_status", length = 50)
    private String sipStatus;

    @Column(name = "srtp_protocol", length = 50)
    private String srtpProtocol;

    @Column(name = "calling_settings_updated_at")
    private Instant callingSettingsUpdatedAt;

    @Column(name = "business_username", length = 100)
    private String businessUsername;

    @Column(name = "business_username_status", length = 50)
    private String businessUsernameStatus;

    @Column(name = "business_username_updated_at")
    private Instant businessUsernameUpdatedAt;

    @Builder.Default
    @Column(name = "bsuid_enabled")
    private Boolean bsuidEnabled = false;

    @Builder.Default
    @Column(name = "parent_bsuid_enabled")
    private Boolean parentBsuidEnabled = false;

    @Builder.Default
    @Column(name = "handover_feature_enabled")
    private Boolean handoverFeatureEnabled = false;

    @Builder.Default
    @Column(name = "meta_business_agent_enabled")
    private Boolean metaBusinessAgentEnabled = false;

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public boolean isHandoverFeatureEnabled() {
        return Boolean.TRUE.equals(handoverFeatureEnabled);
    }

    public boolean isMetaBusinessAgentEnabled() {
        return Boolean.TRUE.equals(metaBusinessAgentEnabled);
    }

    public boolean isBsuidEnabled() {
        return Boolean.TRUE.equals(bsuidEnabled);
    }

    public boolean isParentBsuidEnabled() {
        return Boolean.TRUE.equals(parentBsuidEnabled);
    }

    @PrePersist
    @PreUpdate
    public void onUpdate() {
        super.populateTenant();
        this.updatedAt = Instant.now();
    }
}
