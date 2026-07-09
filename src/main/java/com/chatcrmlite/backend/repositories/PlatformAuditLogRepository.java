package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.PlatformAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface PlatformAuditLogRepository extends JpaRepository<PlatformAuditLog, UUID> {

    @Query("""
        SELECT a FROM PlatformAuditLog a
        WHERE (cast(:action as string) IS NULL OR a.action = :action)
          AND (cast(:targetType as string) IS NULL OR a.targetType = :targetType)
          AND (cast(:from as timestamp) IS NULL OR a.timestamp >= :from)
          AND (cast(:to as timestamp) IS NULL OR a.timestamp <= :to)
        ORDER BY a.timestamp DESC
        """)
    Page<PlatformAuditLog> findFiltered(
        @Param("action") String action,
        @Param("targetType") String targetType,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        Pageable pageable
    );

    /** Recent activity feed — last N events across all action types. */
    @Query("SELECT a FROM PlatformAuditLog a ORDER BY a.timestamp DESC")
    Page<PlatformAuditLog> findRecent(Pageable pageable);
}
