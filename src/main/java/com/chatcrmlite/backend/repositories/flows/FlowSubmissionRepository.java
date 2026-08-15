package com.chatcrmlite.backend.repositories.flows;

import com.chatcrmlite.backend.models.flows.FlowSubmission;
import com.chatcrmlite.backend.models.flows.SubmissionProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FlowSubmissionRepository extends JpaRepository<FlowSubmission, UUID> {

    @Query("SELECT s FROM FlowSubmission s WHERE s.tenant.id = :tenantId AND s.eventId = :eventId")
    Optional<FlowSubmission> findByTenantIdAndEventId(@Param("tenantId") UUID tenantId, @Param("eventId") String eventId);

    @Query("SELECT s FROM FlowSubmission s WHERE s.flow.id = :flowId ORDER BY s.createdAt DESC")
    List<FlowSubmission> findAllByFlowIdOrderByCreatedAtDesc(@Param("flowId") UUID flowId);

    @Query("SELECT s FROM FlowSubmission s WHERE s.tenant.id = :tenantId ORDER BY s.createdAt DESC")
    List<FlowSubmission> findAllByTenantIdOrderByCreatedAtDesc(@Param("tenantId") UUID tenantId);

    @Query("SELECT s FROM FlowSubmission s WHERE s.processingStatus = :status ORDER BY s.createdAt ASC")
    List<FlowSubmission> findAllByProcessingStatus(@Param("status") SubmissionProcessingStatus status);
}
