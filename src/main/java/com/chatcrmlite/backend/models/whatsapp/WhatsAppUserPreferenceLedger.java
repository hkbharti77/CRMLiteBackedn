package com.chatcrmlite.backend.models.whatsapp;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_user_preferences_ledger", uniqueConstraints = {
    @UniqueConstraint(name = "uq_whatsapp_user_preference_event", columnNames = {"tenant_id", "canonical_fingerprint"})
}, indexes = {
    @Index(name = "idx_wa_pref_lookup", columnList = "tenant_id, user_id, category")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WhatsAppUserPreferenceLedger extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "waba_id", length = 100)
    private String wabaId;

    @Column(name = "phone_number_id", length = 100)
    private String phoneNumberId;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "parent_user_id", length = 128)
    private String parentUserId;

    @Column(name = "wa_id", length = 32)
    private String waId;

    @Column(name = "category", nullable = false, length = 100)
    private String category;

    @Column(name = "preference_value", nullable = false, length = 50)
    private String preferenceValue;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "canonical_fingerprint", nullable = false, length = 128)
    private String canonicalFingerprint;

    @Column(name = "raw_payload_json", columnDefinition = "TEXT")
    private String rawPayloadJson;

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
