package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.MetaConversionEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetaConversionEventRepository extends JpaRepository<MetaConversionEvent, UUID> {

    Optional<MetaConversionEvent> findByTenantIdAndId(UUID tenantId, UUID id);
    Optional<MetaConversionEvent> findByTenantIdAndEventId(UUID tenantId, String eventId);

    @Query(value = "SELECT * FROM meta_conversion_events e WHERE e.status IN ('PENDING', 'RETRY') AND (e.next_attempt_at IS NULL OR e.next_attempt_at <= :now) AND (e.locked_at IS NULL OR e.locked_at < :leaseExpiry) ORDER BY e.created_at ASC LIMIT 50 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<MetaConversionEvent> findClaimableEvents(@Param("now") LocalDateTime now, @Param("leaseExpiry") LocalDateTime leaseExpiry);
}
