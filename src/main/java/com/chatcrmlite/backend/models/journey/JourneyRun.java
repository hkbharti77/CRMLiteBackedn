package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "journey_runs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JourneyRun {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "journey_id", nullable = false)
    private UUID journeyId;

    @Column(name = "journey_version_id", nullable = false)
    private UUID journeyVersionId;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    @Column(name = "trigger_event_id", nullable = false)
    private UUID triggerEventId;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Builder.Default
    @Column(name = "status", length = 50)
    private String status = "RUNNING";

    @Builder.Default
    @Column(name = "context_data", columnDefinition = "jsonb")
    private String contextData = "{}";

    @CreationTimestamp
    @Column(name = "started_at", updatable = false)
    private ZonedDateTime startedAt;

    @Column(name = "completed_at")
    private ZonedDateTime completedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;
}
