package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.whatsapp.CallLimitEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface CallLimitEventRepository extends JpaRepository<CallLimitEvent, UUID> {

    @Query("SELECT COUNT(e) FROM CallLimitEvent e WHERE e.tenant.id = :tenantId AND e.phoneNumberId = :phoneNumberId AND e.eventType = :eventType AND e.occurredAt >= :since")
    long countEventsSince(
        @Param("tenantId") UUID tenantId,
        @Param("phoneNumberId") String phoneNumberId,
        @Param("eventType") String eventType,
        @Param("since") Instant since
    );

    @Query("SELECT COUNT(e) FROM CallLimitEvent e WHERE e.tenant.id = :tenantId AND e.phoneNumberId = :phoneNumberId AND e.userWaId = :userWaId AND e.eventType = :eventType AND e.occurredAt >= :since")
    long countUserEventsSince(
        @Param("tenantId") UUID tenantId,
        @Param("phoneNumberId") String phoneNumberId,
        @Param("userWaId") String userWaId,
        @Param("eventType") String eventType,
        @Param("since") Instant since
    );
}
