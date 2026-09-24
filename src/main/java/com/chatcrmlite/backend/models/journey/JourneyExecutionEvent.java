package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "journey_execution_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JourneyExecutionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "journey_run_id")
    private UUID journeyRunId;

    @Column(name = "node_execution_id")
    private UUID nodeExecutionId;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "channel", length = 50)
    private String channel;

    @Column(name = "provider", length = 50)
    private String provider;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "template_id", length = 100)
    private String templateId;

    @Column(name = "message_hash", length = 64)
    private String messageHash;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "request_metadata", columnDefinition = "jsonb")
    private String requestMetadata;

    @Column(name = "response_metadata", columnDefinition = "jsonb")
    private String responseMetadata;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;
}
