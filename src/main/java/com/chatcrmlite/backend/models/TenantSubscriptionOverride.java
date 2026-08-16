package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenant_subscription_overrides", indexes = {
    @Index(name = "idx_override_tenant_model", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class TenantSubscriptionOverride extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(columnDefinition = "jsonb")
    private String featureOverrides;

    @Column(columnDefinition = "jsonb")
    private String quotaOverrides;

    @Column(columnDefinition = "jsonb")
    private String priorityOverrides;

    @Column(columnDefinition = "jsonb")
    private String pricingOverrides;

    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveUntil;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "tenant_page_overrides", joinColumns = @JoinColumn(name = "override_id"))
    @MapKeyColumn(name = "page_key", length = 64)
    @Column(name = "action", length = 16)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private java.util.Map<String, com.chatcrmlite.backend.models.entitlements.OverrideAction> pageOverrides = new java.util.HashMap<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "tenant_setting_overrides", joinColumns = @JoinColumn(name = "override_id"))
    @MapKeyColumn(name = "setting_key", length = 64)
    @Column(name = "action", length = 16)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private java.util.Map<String, com.chatcrmlite.backend.models.entitlements.OverrideAction> settingOverrides = new java.util.HashMap<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "tenant_service_overrides", joinColumns = @JoinColumn(name = "override_id"))
    @MapKeyColumn(name = "service_key", length = 64)
    @Column(name = "action", length = 16)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private java.util.Map<String, com.chatcrmlite.backend.models.entitlements.OverrideAction> serviceOverrides = new java.util.HashMap<>();

    @Version
    @Column(name = "entity_version", nullable = false)
    @Builder.Default
    private Long entityVersion = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    private String createdBy;
    private String updatedBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (version == null) version = 1;
        if (entityVersion == null) entityVersion = 0L;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
