package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rag_crawl_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagCrawlJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "source_url", nullable = false, length = 2048)
    private String sourceUrl;

    @Column(name = "crawl_type", nullable = false, length = 32)
    private String crawlType;

    @Column(nullable = false, length = 32)
    @Builder.Default
    private String status = "QUEUED";

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "claim_generation", nullable = false)
    @Builder.Default
    private Integer claimGeneration = 0;

    @Column(name = "pages_discovered")
    @Builder.Default
    private Integer pagesDiscovered = 0;

    @Column(name = "pages_crawled")
    @Builder.Default
    private Integer pagesCrawled = 0;

    @Column(name = "pages_failed")
    @Builder.Default
    private Integer pagesFailed = 0;

    @Column(name = "total_chunks")
    @Builder.Default
    private Integer totalChunks = 0;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "document_name", length = 512)
    private String documentName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "partial_failures_json", columnDefinition = "jsonb")
    private String partialFailuresJson;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "locked_by", length = 128)
    private String lockedBy;

    @Column(name = "attempt_count")
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
