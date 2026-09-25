package com.chatcrmlite.backend.models.whatsapp;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "call_limit_events", indexes = {
    @Index(name = "idx_call_limit_events_lookup", columnList = "tenant_id, phone_number_id, user_wa_id, occurred_at"),
    @Index(name = "idx_call_limit_events_tenant_time", columnList = "tenant_id, phone_number_id, occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class CallLimitEvent extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "phone_number_id", nullable = false, length = 64)
    private String phoneNumberId;

    @Column(name = "user_wa_id", length = 32)
    private String userWaId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType; // PERMISSION_REQUEST, CALL_INITIATED, CALL_CONNECTED, CALL_UNANSWERED, CALL_REJECTED

    @Column(name = "call_id", length = 128)
    private String callId;

    @Builder.Default
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @PrePersist
    public void onPrePersist() {
        super.populateTenant();
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
