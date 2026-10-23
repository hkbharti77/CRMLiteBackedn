package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignTrackedLink;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppCampaignTrackedLinkRepository extends JpaRepository<WhatsAppCampaignTrackedLink, UUID> {

    @EntityGraph(attributePaths = {"campaign", "recipient"})
    Optional<WhatsAppCampaignTrackedLink> findByToken(String token);

    List<WhatsAppCampaignTrackedLink> findByCampaign(WhatsAppCampaign campaign);

    long countByCampaign(WhatsAppCampaign campaign);

    @Query("SELECT COUNT(DISTINCT l.recipient.id) FROM WhatsAppCampaignTrackedLink l WHERE l.campaign = :campaign AND l.clickCount > 0")
    long countDistinctClickedRecipientsByCampaign(@Param("campaign") WhatsAppCampaign campaign);

    @Query("SELECT COALESCE(SUM(l.clickCount), 0) FROM WhatsAppCampaignTrackedLink l WHERE l.campaign = :campaign")
    long sumClicksByCampaign(@Param("campaign") WhatsAppCampaign campaign);

    @Modifying
    @Query("UPDATE WhatsAppCampaignTrackedLink l SET l.clickCount = l.clickCount + 1, l.lastClickedAt = :now, l.firstClickedAt = COALESCE(l.firstClickedAt, :now) WHERE l.id = :id")
    void recordClick(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM WhatsAppCampaignTrackedLink l WHERE l.campaign = :campaign")
    void deleteByCampaign(@Param("campaign") WhatsAppCampaign campaign);
}
