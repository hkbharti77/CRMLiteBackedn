package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rag_quota_reservations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQuotaReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "job_id", nullable = false, unique = true)
    private UUID jobId;

    @Column(name = "pages_reserved", nullable = false)
    private Integer pagesReserved;

    @Column(name = "pages_consumed")
    @Builder.Default
    private Integer pagesConsumed = 0;

    @Column(name = "chunks_reserved", nullable = false)
    @Builder.Default
    private Integer chunksReserved = 0;

    @Column(name = "chunks_consumed")
    @Builder.Default
    private Integer chunksConsumed = 0;

    @Column(nullable = false, length = 32)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
