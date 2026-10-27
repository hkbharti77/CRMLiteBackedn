package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignRecipient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppCampaignRecipientRepository extends JpaRepository<WhatsAppCampaignRecipient, UUID> {
    @Override
    @EntityGraph(attributePaths = {"campaign", "campaign.templateSnapshot", "campaign.owner"})
    Optional<WhatsAppCampaignRecipient> findById(UUID id);

    List<WhatsAppCampaignRecipient> findByCampaignAndStatus(WhatsAppCampaign campaign, WhatsAppCampaignRecipient.RecipientStatus status, Pageable pageable);
    List<WhatsAppCampaignRecipient> findByCampaignAndIsHoldoutAndStatus(WhatsAppCampaign campaign, Boolean isHoldout, WhatsAppCampaignRecipient.RecipientStatus status, Pageable pageable);
    List<WhatsAppCampaignRecipient> findByCampaignAndIsHoldout(WhatsAppCampaign campaign, Boolean isHoldout);
    List<WhatsAppCampaignRecipient> findByVariant(com.chatcrmlite.backend.models.WhatsAppCampaignVariant variant);
    org.springframework.data.domain.Page<WhatsAppCampaignRecipient> findByCampaign(WhatsAppCampaign campaign, Pageable pageable);
    Optional<WhatsAppCampaignRecipient> findByWaMessageId(String waMessageId);
    boolean existsByCampaignIdAndContactId(UUID campaignId, UUID contactId);
    long countByCampaignId(UUID campaignId);
    long countByCampaignAndStatus(WhatsAppCampaign campaign, WhatsAppCampaignRecipient.RecipientStatus status);
    long countByCampaignAndStatusIn(WhatsAppCampaign campaign, java.util.Collection<WhatsAppCampaignRecipient.RecipientStatus> statuses);
    long countByVariant(com.chatcrmlite.backend.models.WhatsAppCampaignVariant variant);
    long countByCampaignAndIsHoldout(WhatsAppCampaign campaign, Boolean isHoldout);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE WhatsAppCampaignRecipient r SET r.clickCount = COALESCE(r.clickCount, 0) + 1, r.lastClickedAt = :now, r.firstClickedAt = COALESCE(r.firstClickedAt, :now) WHERE r.id = :id")
    void recordClick(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM WhatsAppCampaignRecipient r WHERE r.campaign = :campaign")
    void deleteByCampaign(@org.springframework.data.repository.query.Param("campaign") WhatsAppCampaign campaign);
}
