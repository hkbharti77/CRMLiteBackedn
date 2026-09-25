package com.chatcrmlite.backend.models.whatsapp;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_call_sessions", uniqueConstraints = {
    @UniqueConstraint(name = "uq_wa_call_session_id", columnNames = {"tenant_id", "call_id"})
}, indexes = {
    @Index(name = "idx_wa_call_sessions_tenant_state", columnList = "tenant_id, state"),
    @Index(name = "idx_wa_call_sessions_call_id", columnList = "tenant_id, call_id"),
    @Index(name = "idx_wa_call_sessions_phone", columnList = "tenant_id, phone_number_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WhatsAppCallSession extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "waba_id", nullable = false, length = 64)
    private String wabaId;

    @Column(name = "phone_number_id", nullable = false, length = 64)
    private String phoneNumberId;

    @Column(name = "call_id", nullable = false, length = 128, unique = true)
    private String callId;

    @Column(name = "direction", nullable = false, length = 32)
    private String direction; // USER_INITIATED, BUSINESS_INITIATED

    @Column(name = "from_wa_id", nullable = false, length = 32)
    private String fromWaId;

    @Column(name = "to_wa_id", nullable = false, length = 32)
    private String toWaId;

    @Builder.Default
    @Column(name = "signaling_mode", nullable = false, length = 16)
    private String signalingMode = "GRAPH"; // GRAPH, SIP

    @Builder.Default
    @Column(name = "media_mode", nullable = false, length = 16)
    private String mediaMode = "WEBRTC"; // WEBRTC, SIP_TLS

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "permission_state", length = 32)
    private String permissionState;

    @Column(name = "sdp_offer_hash", length = 64)
    private String sdpOfferHash;

    @Column(name = "sdp_answer_hash", length = 64)
    private String sdpAnswerHash;

    @Column(name = "remote_fingerprint", length = 128)
    private String remoteFingerprint;

    @Column(name = "ice_session_id", length = 128)
    private String iceSessionId;

    @Builder.Default
    @Column(name = "codec", length = 16)
    private String codec = "Opus";

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Builder.Default
    @Column(name = "duration_seconds")
    private Integer durationSeconds = 0;

    @Column(name = "termination_source", length = 32)
    private String terminationSource; // USER, AGENT, SYSTEM, ERROR

    @Column(name = "termination_reason", length = 64)
    private String terminationReason;

    @Column(name = "meta_error_code")
    private Integer metaErrorCode;

    @Column(name = "meta_error_title", length = 128)
    private String metaErrorTitle;

    @Column(name = "meta_error_message")
    private String metaErrorMessage;

    @Column(name = "meta_request_id", length = 128)
    private String metaRequestId;

    @Column(name = "failure_stage", length = 64)
    private String failureStage;

    @Column(name = "ai_session_id")
    private UUID aiSessionId;

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
