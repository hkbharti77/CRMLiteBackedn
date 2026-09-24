package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.JourneyRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JourneyRunRepository extends JpaRepository<JourneyRun, UUID> {

    List<JourneyRun> findByBusinessId(String businessId);

    List<JourneyRun> findByBusinessIdAndContactId(String businessId, UUID contactId);

    Optional<JourneyRun> findByJourneyIdAndTriggerEventIdAndContactId(UUID journeyId, UUID triggerEventId, UUID contactId);

    List<JourneyRun> findByBusinessIdAndContactIdAndStatus(String businessId, UUID contactId, String status);

    Optional<JourneyRun> findByIdAndBusinessId(UUID id, String businessId);
}
