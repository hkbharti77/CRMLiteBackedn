package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.JourneyExecutionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JourneyExecutionEventRepository extends JpaRepository<JourneyExecutionEvent, UUID> {

    List<JourneyExecutionEvent> findByJourneyRunId(UUID journeyRunId);

    List<JourneyExecutionEvent> findByCorrelationId(String correlationId);

    List<JourneyExecutionEvent> findByBusinessIdAndJourneyRunId(String businessId, UUID journeyRunId);
}
