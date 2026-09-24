package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.JourneyOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository("journeyOutboxEventRepository")
public interface JourneyOutboxEventRepository extends JpaRepository<JourneyOutboxEvent, UUID> {

    Optional<JourneyOutboxEvent> findByEventId(UUID eventId);

    List<JourneyOutboxEvent> findByBusinessIdAndStatus(String businessId, String status);

    @Query(value = "SELECT * FROM journey_outbox_events " +
            "WHERE status IN ('PENDING', 'FAILED') " +
            "AND next_attempt_at <= :now " +
            "AND (lock_lease_until IS NULL OR lock_lease_until < :now) " +
            "ORDER BY next_attempt_at ASC " +
            "LIMIT :limit " +
            "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<JourneyOutboxEvent> claimPendingEvents(@Param("now") ZonedDateTime now, @Param("limit") int limit);
}
