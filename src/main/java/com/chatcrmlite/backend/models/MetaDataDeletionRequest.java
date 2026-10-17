package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meta_data_deletion_requests", indexes = {
    @Index(name = "idx_meta_deletion_code", columnList = "confirmation_code"),
    @Index(name = "idx_meta_deletion_user", columnList = "meta_user_id"),
    @Index(name = "idx_meta_deletion_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetaDataDeletionRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "confirmation_code", nullable = false, unique = true, length = 64)
    private String confirmationCode;

    @Column(name = "meta_user_id", nullable = false, length = 100)
    private String metaUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String status = "PENDING"; // PENDING, IN_PROGRESS, COMPLETED, FAILED

    @Column(name = "requested_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "data_summary", columnDefinition = "TEXT")
    private String dataSummary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
