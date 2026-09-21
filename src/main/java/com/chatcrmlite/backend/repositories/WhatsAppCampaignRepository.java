package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppCampaignRepository extends JpaRepository<WhatsAppCampaign, UUID> {
    @EntityGraph(attributePaths = {"templateSnapshot", "owner"})
    Page<WhatsAppCampaign> findByOwner(User owner, Pageable pageable);

    @EntityGraph(attributePaths = {"templateSnapshot", "owner"})
    @org.springframework.data.jpa.repository.Query("SELECT c FROM WhatsAppCampaign c WHERE c.tenant.id = :tenantId")
    Page<WhatsAppCampaign> findByTenantId(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId, Pageable pageable);

    @EntityGraph(attributePaths = {"templateSnapshot", "owner"})
    @org.springframework.data.jpa.repository.Query("SELECT c FROM WhatsAppCampaign c WHERE c.id = :id AND c.tenant.id = :tenantId")
    Optional<WhatsAppCampaign> findByIdAndTenantId(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);

    @Override
    @EntityGraph(attributePaths = {"templateSnapshot", "owner"})
    Optional<WhatsAppCampaign> findById(UUID id);

    List<WhatsAppCampaign> findByStatusAndScheduledAtBefore(WhatsAppCampaign.Status status, LocalDateTime now);
    long countByOwnerAndStatus(User owner, WhatsAppCampaign.Status status);

    /**
     * Returns only campaigns with the given status — pushes the filter to the DB.
     */
    List<WhatsAppCampaign> findAllByStatus(WhatsAppCampaign.Status status);

    @org.springframework.data.jpa.repository.Query("SELECT c FROM WhatsAppCampaign c WHERE c.tenant.id = :tenantId AND (c.templateSnapshot.originalTemplateId = :templateId OR c.templateSnapshot.metaTemplateId = :metaTemplateId) AND c.status IN :statuses")
    List<WhatsAppCampaign> findAllByTenantIdAndTemplateIdAndStatusIn(
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
            @org.springframework.data.repository.query.Param("templateId") UUID templateId,
            @org.springframework.data.repository.query.Param("metaTemplateId") String metaTemplateId,
            @org.springframework.data.repository.query.Param("statuses") List<WhatsAppCampaign.Status> statuses);
}
