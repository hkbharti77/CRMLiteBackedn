package com.chatcrmlite.backend.repositories.flows;

import com.chatcrmlite.backend.models.flows.FlowRevision;
import com.chatcrmlite.backend.models.flows.FlowRevisionStatus;
import com.chatcrmlite.backend.models.flows.RevisionStatus;
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
    Optional<FlowRevision> findByFlowIdAndVersionNumber(@Param("flowId") UUID flowId,
                                                         @Param("versionNumber") int versionNumber);

    @Query("SELECT COALESCE(MAX(r.versionNumber), 0) FROM FlowRevision r WHERE r.flow.id = :flowId")
    int findMaxVersionByFlowId(@Param("flowId") UUID flowId);

    /** Legacy query using old RevisionStatus — kept for backward compatibility during migration. */
    @Query("SELECT r FROM FlowRevision r WHERE r.flow.id = :flowId AND r.status = :status")
    List<FlowRevision> findAllByFlowIdAndStatus(@Param("flowId") UUID flowId,
                                                 @Param("status") RevisionStatus status);

    // ── New methods for FlowPublishWorker ──────────────────────────────────────

    /**
     * Used by the publish worker to detect concurrent publish attempts.
     * Returns true if any revision for this flow is currently in PUBLISHING state.
     */
    @Query("SELECT COUNT(r) > 0 FROM FlowRevision r WHERE r.flow.id = :flowId AND r.revisionStatus = :status")
    boolean existsByFlowIdAndRevisionStatus(@Param("flowId") UUID flowId,
                                             @Param("status") FlowRevisionStatus status);

    /**
     * Looks up a revision by its Meta Flow container ID.
     * Used in the deprecation tracking and timeout recovery paths.
     */
    @Query("SELECT r FROM FlowRevision r WHERE r.metaFlowId = :metaFlowId")
    Optional<FlowRevision> findByMetaFlowId(@Param("metaFlowId") String metaFlowId);

    /**
     * Returns all SUPERSEDED revisions that still have a DEPRECATION_PENDING status,
     * used by the deprecation retry scheduler.
     */
    @Query("SELECT r FROM FlowRevision r WHERE r.revisionStatus = 'SUPERSEDED' AND r.deprecationStatus = 'DEPRECATION_PENDING'")
    List<FlowRevision> findAllPendingDeprecation();
}
