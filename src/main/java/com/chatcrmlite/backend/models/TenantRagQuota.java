package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_rag_quotas")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantRagQuota implements Persistable<UUID> {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return tenantId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Column(name = "daily_page_limit", nullable = false)
    @Builder.Default
    private Integer dailyPageLimit = 50;

    @Column(name = "daily_pages_consumed", nullable = false)
    @Builder.Default
    private Integer dailyPagesConsumed = 0;

    @Column(name = "monthly_chunk_limit", nullable = false)
    @Builder.Default
    private Integer monthlyChunkLimit = 2500;

    @Column(name = "monthly_chunks_consumed", nullable = false)
    @Builder.Default
    private Integer monthlyChunksConsumed = 0;

    @Column(name = "timezone", nullable = false, length = 64)
    @Builder.Default
    private String timezone = "UTC";

    @Column(name = "last_daily_reset_at")
    @Builder.Default
    private Instant lastDailyResetAt = Instant.now();

    @Column(name = "last_monthly_reset_at")
    @Builder.Default
    private Instant lastMonthlyResetAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
