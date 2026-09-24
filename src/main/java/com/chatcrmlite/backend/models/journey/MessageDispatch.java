package com.chatcrmlite.backend.models.journey;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "message_dispatches")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageDispatch {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "journey_run_id", nullable = false)
    private UUID journeyRunId;

    @Column(name = "node_execution_id", nullable = false, unique = true)
    private UUID nodeExecutionId;

    @Column(name = "operation_id", nullable = false, unique = true)
    private UUID operationId;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Column(name = "channel", nullable = false, length = 50)
    private String channel;

    @Builder.Default
    @Column(name = "status", length = 50)
    private String status = "QUEUED";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;
}
