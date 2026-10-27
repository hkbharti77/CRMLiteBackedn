package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppCampaignVariantRepository extends JpaRepository<WhatsAppCampaignVariant, UUID> {

    List<WhatsAppCampaignVariant> findByCampaignOrderByVariantLabelAsc(WhatsAppCampaign campaign);

    List<WhatsAppCampaignVariant> findByCampaignId(UUID campaignId);

    List<WhatsAppCampaignVariant> findByCampaignIdOrderByVariantLabelAsc(UUID campaignId);

    Optional<WhatsAppCampaignVariant> findByCampaignIdAndVariantLabel(UUID campaignId, String variantLabel);

    @Modifying
    @Query("UPDATE WhatsAppCampaignVariant v SET v.sentCount = v.sentCount + 1 WHERE v.id = :id")
    int incrementSentCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE WhatsAppCampaignVariant v SET v.deliveredCount = v.deliveredCount + 1 WHERE v.id = :id")
    int incrementDeliveredCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE WhatsAppCampaignVariant v SET v.readCount = v.readCount + 1 WHERE v.id = :id")
    int incrementReadCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE WhatsAppCampaignVariant v SET v.clickCount = v.clickCount + 1 WHERE v.id = :id")
    int incrementClickCount(@Param("id") UUID id);
}
