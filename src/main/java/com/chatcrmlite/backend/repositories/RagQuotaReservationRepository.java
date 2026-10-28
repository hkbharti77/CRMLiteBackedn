package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.RagQuotaReservation;
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
public interface RagQuotaReservationRepository extends JpaRepository<RagQuotaReservation, UUID> {

    Optional<RagQuotaReservation> findByJobId(UUID jobId);

    Optional<RagQuotaReservation> findByJobIdAndStatus(UUID jobId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RagQuotaReservation r WHERE r.jobId = :jobId AND r.status = 'PENDING'")
    Optional<RagQuotaReservation> findPendingByJobIdForUpdate(@Param("jobId") UUID jobId);

    @Query("SELECT COALESCE(SUM(r.pagesReserved), 0) FROM RagQuotaReservation r " +
           "WHERE r.tenantId = :tenantId AND r.status = 'PENDING' AND r.expiresAt > :now")
    int sumPendingPagesByTenantId(@Param("tenantId") UUID tenantId, @Param("now") Instant now);

    @Query("SELECT COALESCE(SUM(r.chunksReserved), 0) FROM RagQuotaReservation r " +
           "WHERE r.tenantId = :tenantId AND r.status = 'PENDING' AND r.expiresAt > :now")
    int sumPendingChunksByTenantId(@Param("tenantId") UUID tenantId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RagQuotaReservation r SET r.status = 'COMMITTED', r.pagesConsumed = :pages, " +
           "r.chunksConsumed = :chunks, r.updatedAt = :now " +
           "WHERE r.jobId = :jobId AND r.status = 'PENDING'")
    int commitPendingReservation(@Param("jobId") UUID jobId, 
                                 @Param("pages") Integer pages, 
                                 @Param("chunks") Integer chunks, 
                                 @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RagQuotaReservation r SET r.status = 'RELEASED', r.updatedAt = :now " +
           "WHERE r.jobId = :jobId AND r.status = 'PENDING'")
    int releasePendingReservation(@Param("jobId") UUID jobId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RagQuotaReservation r SET r.expiresAt = :newExpiresAt, r.updatedAt = :now " +
           "WHERE r.jobId = :jobId AND r.status = 'PENDING'")
    int renewPendingReservation(@Param("jobId") UUID jobId, 
                                @Param("newExpiresAt") Instant newExpiresAt, 
                                @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RagQuotaReservation r SET r.status = 'EXPIRED', r.updatedAt = :now " +
           "WHERE r.status = 'PENDING' AND r.expiresAt < :now")
    int expireStaleReservations(@Param("now") Instant now);
}
