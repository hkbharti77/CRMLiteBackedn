package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenant_subscription_override_audits", indexes = {
    @Index(name = "idx_override_audit_tenant_model", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class TenantSubscriptionOverrideAudit extends BaseTenantEntity {

    public enum OverrideAuditAction {
        CREATE_OVERRIDE,
        UPDATE_OVERRIDE,
        RESET_OVERRIDE,
        EXPIRE_OVERRIDE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private OverrideAuditAction action;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String oldValueJson;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String newValueJson;

    @Column(nullable = false, length = 100)
    private String changedBy;

    @Column(columnDefinition = "TEXT")
    private String reason;

    private String requestId;
    private String ipAddress;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
