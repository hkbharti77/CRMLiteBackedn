package com.chatcrmlite.backend.repositories.flows;

import com.chatcrmlite.backend.models.flows.FlowRevision;
import com.chatcrmlite.backend.models.flows.RevisionStatus;
import com.chatcrmlite.backend.models.flows.WhatsAppFlow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FlowRevisionRepository extends JpaRepository<FlowRevision, UUID> {

    @Query("SELECT r FROM FlowRevision r WHERE r.flow.id = :flowId ORDER BY r.versionNumber DESC")
    List<FlowRevision> findAllByFlowIdOrderByVersionDesc(@Param("flowId") UUID flowId);

    @Query("SELECT r FROM FlowRevision r WHERE r.flow.id = :flowId AND r.versionNumber = :versionNumber")
    Optional<FlowRevision> findByFlowIdAndVersionNumber(@Param("flowId") UUID flowId, @Param("versionNumber") int versionNumber);

    @Query("SELECT COALESCE(MAX(r.versionNumber), 0) FROM FlowRevision r WHERE r.flow.id = :flowId")
    int findMaxVersionByFlowId(@Param("flowId") UUID flowId);

    @Query("SELECT r FROM FlowRevision r WHERE r.flow.id = :flowId AND r.status = :status")
    List<FlowRevision> findAllByFlowIdAndStatus(@Param("flowId") UUID flowId, @Param("status") RevisionStatus status);
}
