package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.CampaignPauseOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

@Repository
public interface CampaignPauseOutboxRepository extends JpaRepository<CampaignPauseOutbox, UUID> {
    List<CampaignPauseOutbox> findTop50ByStatusOrderByCreatedAtAsc(String status);

    @Query("SELECT COUNT(c) > 0 FROM CampaignPauseOutbox c WHERE c.tenant.id = :tenantId AND c.template.id = :templateId AND c.sourceEventId = :sourceEventId")
    boolean existsByTenantIdAndTemplateIdAndSourceEventId(
            @Param("tenantId") UUID tenantId,
            @Param("templateId") UUID templateId,
            @Param("sourceEventId") String sourceEventId);
}
