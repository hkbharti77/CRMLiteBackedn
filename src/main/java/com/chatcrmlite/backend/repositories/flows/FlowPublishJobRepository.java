package com.chatcrmlite.backend.repositories.flows;

import com.chatcrmlite.backend.models.flows.FlowPublishJob;
import com.chatcrmlite.backend.models.flows.PublishJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface FlowPublishJobRepository extends JpaRepository<FlowPublishJob, UUID> {

    @Query("SELECT j FROM FlowPublishJob j JOIN FETCH j.flow f LEFT JOIN FETCH f.tenant LEFT JOIN FETCH j.revision r WHERE (j.status = com.chatcrmlite.backend.models.flows.PublishJobStatus.PENDING OR j.status = com.chatcrmlite.backend.models.flows.PublishJobStatus.PROCESSING) AND (j.nextRetryAt IS NULL OR j.nextRetryAt <= :now) ORDER BY j.createdAt ASC")
    List<FlowPublishJob> findDueJobs(@Param("now") LocalDateTime now);

    @Query("SELECT j FROM FlowPublishJob j WHERE j.flow.id = :flowId ORDER BY j.createdAt DESC")
    List<FlowPublishJob> findAllByFlowIdOrderByCreatedAtDesc(@Param("flowId") UUID flowId);
}
