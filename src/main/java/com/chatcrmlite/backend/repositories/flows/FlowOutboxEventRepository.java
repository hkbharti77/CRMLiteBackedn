package com.chatcrmlite.backend.repositories.flows;

import com.chatcrmlite.backend.models.flows.FlowOutboxEvent;
import com.chatcrmlite.backend.models.flows.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FlowOutboxEventRepository extends JpaRepository<FlowOutboxEvent, UUID> {

    @Query("SELECT e FROM FlowOutboxEvent e WHERE e.status = :status AND e.retryCount < :maxRetries ORDER BY e.createdAt ASC")
    List<FlowOutboxEvent> findPendingEvents(@Param("status") OutboxStatus status, @Param("maxRetries") int maxRetries);

    @Query("SELECT e FROM FlowOutboxEvent e WHERE e.aggregateType = :aggregateType AND e.aggregateId = :aggregateId")
    List<FlowOutboxEvent> findAllByAggregate(@Param("aggregateType") String aggregateType, @Param("aggregateId") UUID aggregateId);
}
