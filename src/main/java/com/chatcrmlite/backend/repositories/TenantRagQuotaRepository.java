package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.TenantRagQuota;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRagQuotaRepository extends JpaRepository<TenantRagQuota, UUID> {

    @Modifying
    @Query(value = "INSERT INTO tenant_rag_quotas (tenant_id, daily_page_limit, daily_pages_consumed, monthly_chunk_limit, monthly_chunks_consumed, timezone, last_daily_reset_at, last_monthly_reset_at, updated_at) " +
                   "VALUES (:tenantId, :dailyLimit, 0, :chunkLimit, 0, 'UTC', NOW(), NOW(), NOW()) " +
                   "ON CONFLICT (tenant_id) DO NOTHING", nativeQuery = true)
    int insertIfNotExists(@Param("tenantId") UUID tenantId, 
                          @Param("dailyLimit") int dailyLimit, 
                          @Param("chunkLimit") int chunkLimit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM TenantRagQuota q WHERE q.tenantId = :tenantId")
    Optional<TenantRagQuota> findByTenantIdForUpdate(@Param("tenantId") UUID tenantId);

    @Modifying
    @Query("UPDATE TenantRagQuota q SET q.dailyPagesConsumed = q.dailyPagesConsumed + :pages, " +
           "q.monthlyChunksConsumed = q.monthlyChunksConsumed + :chunks, q.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE q.tenantId = :tenantId")
    int incrementConsumedCounters(@Param("tenantId") UUID tenantId, 
                                  @Param("pages") Integer pages, 
                                  @Param("chunks") Integer chunks);

    @Modifying
    @Query("UPDATE TenantRagQuota q SET q.dailyPagesConsumed = 0, q.lastDailyResetAt = :now, q.updatedAt = :now " +
           "WHERE q.tenantId = :tenantId")
    int resetDailyPages(@Param("tenantId") UUID tenantId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE TenantRagQuota q SET q.monthlyChunksConsumed = 0, q.lastMonthlyResetAt = :now, q.updatedAt = :now " +
           "WHERE q.tenantId = :tenantId")
    int resetMonthlyChunks(@Param("tenantId") UUID tenantId, @Param("now") Instant now);
}
