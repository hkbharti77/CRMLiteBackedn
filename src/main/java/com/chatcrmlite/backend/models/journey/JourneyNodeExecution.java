package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "journey_node_executions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JourneyNodeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "journey_run_id", nullable = false)
    private UUID journeyRunId;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;

    @Column(name = "node_type", nullable = false, length = 50)
    private String nodeType;

    @Builder.Default
    @Column(name = "status", length = 50)
    private String status = "PENDING";

    @Column(name = "scheduled_at")
    private ZonedDateTime scheduledAt;

    @Column(name = "started_at")
    private ZonedDateTime startedAt;

    @Column(name = "completed_at")
    private ZonedDateTime completedAt;

    @Builder.Default
    @Column(name = "attempt_count")
    private Integer attemptCount = 0;

    @Builder.Default
    @Column(name = "max_attempts")
    private Integer maxAttempts = 3;

    @Column(name = "locked_at")
    private ZonedDateTime lockedAt;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "lock_lease_until")
    private ZonedDateTime lockLeaseUntil;

    @Builder.Default
    @Column(name = "result_data", columnDefinition = "jsonb")
    private String resultData = "{}";

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;
}
