package com.chatcrmlite.backend.models.whatsapp;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_call_permissions", uniqueConstraints = {
    @UniqueConstraint(name = "uq_wa_call_permission", columnNames = {"tenant_id", "phone_number_id", "user_wa_id"})
}, indexes = {
    @Index(name = "idx_wa_call_perm_tenant_user", columnList = "tenant_id, user_wa_id, status"),
    @Index(name = "idx_wa_call_perm_phone_status", columnList = "tenant_id, phone_number_id, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WhatsAppCallPermission extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "phone_number_id", nullable = false, length = 64)
    private String phoneNumberId;

    @Column(name = "user_wa_id", nullable = false, length = 32)
    private String userWaId;

    @Column(name = "status", nullable = false, length = 32)
    private String status; // PENDING, GRANTED_TEMPORARY, GRANTED_PERMANENT, DENIED, REVOKED, EXPIRED

    @Builder.Default
    @Column(name = "permission_type", nullable = false, length = 32)
    private String permissionType = "TEMPORARY"; // TEMPORARY, PERMANENT

    @Builder.Default
    @Column(name = "is_permanent")
    private Boolean isPermanent = false;

    @Column(name = "response_source", length = 32)
    private String responseSource; // USER_RESPONSE, AUTO_CALLBACK, AUTO_REVOCATION

    @Column(name = "granted_at")
    private Instant grantedAt;

    @Column(name = "expires_at")
    private Instant expiresAt; // Single source of truth for expiration

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_permission_webhook_at")
    private Instant lastPermissionWebhookAt;

    @Column(name = "source", length = 64)
    private String source;

    @Column(name = "last_permission_request_at")
    private Instant lastPermissionRequestAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    @PreUpdate
    public void onUpdate() {
        super.populateTenant();
        this.updatedAt = Instant.now();
    }
}
