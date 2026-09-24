package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity(name = "JourneyOutboxEvent")
@Table(name = "journey_outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JourneyOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Builder.Default
    @Column(name = "status", length = 50)
    private String status = "PENDING";

    @Column(name = "claimed_at")
    private ZonedDateTime claimedAt;

    @Column(name = "claimed_by", length = 100)
    private String claimedBy;

    @Column(name = "lock_lease_until")
    private ZonedDateTime lockLeaseUntil;

    @Builder.Default
    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "next_attempt_at")
    private ZonedDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "processed_at")
    private ZonedDateTime processedAt;
}
