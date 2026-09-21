package com.chatcrmlite.backend.models.whatsapp;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_handover_audit_ledger", uniqueConstraints = {
    @UniqueConstraint(name = "uq_whatsapp_handover_event", columnNames = {"tenant_id", "fingerprint"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WhatsAppHandoverAuditLedger extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "phone_number_id", length = 100)
    private String phoneNumberId;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "wa_id", length = 32)
    private String waId;

    @Column(name = "owner_app_id", length = 100)
    private String ownerAppId;

    @Column(name = "previous_owner_app_id", length = 100)
    private String previousOwnerAppId;

    @Column(name = "control_event", nullable = false, length = 100)
    private String controlEvent;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "fingerprint", nullable = false, length = 128)
    private String fingerprint;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
