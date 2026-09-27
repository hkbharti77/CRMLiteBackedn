package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "meta_conversion_events", indexes = {
    @Index(name = "idx_meta_conv_tenant_status_next", columnList = "tenant_id, status, next_attempt_at"),
    @Index(name = "idx_meta_conv_tenant_event_id", columnList = "tenant_id, event_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetaConversionEvent {

    public enum EventStatus {
        PENDING, PROCESSING, RETRY, SUCCESS, DEAD_LETTER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_name", nullable = false)
    private String eventName;

    @Column(name = "source_type")
    private String sourceType; // CTWA, ORGANIC_WHATSAPP

    @Column(name = "waba_id")
    private String wabaId;

    @Column(name = "dataset_id")
    private String datasetId;

    @Column(name = "ctwa_clid")
    private String ctwaClid;

    @Column(name = "payload_version", length = 50)
    private String payloadVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "meta_trace_id")
    private String metaTraceId;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = EventStatus.PENDING;
        }
    }
}
