package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.JourneyNodeExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JourneyNodeExecutionRepository extends JpaRepository<JourneyNodeExecution, UUID> {

    List<JourneyNodeExecution> findByJourneyRunId(UUID journeyRunId);

    Optional<JourneyNodeExecution> findByIdAndBusinessId(UUID id, String businessId);

    @Query(value = "SELECT * FROM journey_node_executions " +
            "WHERE status IN ('PENDING', 'RETRYING', 'WAITING') " +
            "AND scheduled_at <= :now " +
            "AND (lock_lease_until IS NULL OR lock_lease_until < :now) " +
            "ORDER BY scheduled_at ASC " +
            "LIMIT :limit " +
            "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<JourneyNodeExecution> claimPendingNodeExecutions(@Param("now") ZonedDateTime now, @Param("limit") int limit);
}
