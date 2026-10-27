package com.chatcrmlite.backend.repositories.email;

import com.chatcrmlite.backend.models.email.EmailCampaignVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailCampaignVariantRepository extends JpaRepository<EmailCampaignVariant, UUID> {

    List<EmailCampaignVariant> findByCampaignId(UUID campaignId);

    List<EmailCampaignVariant> findByCampaignIdOrderByVariantCodeAsc(UUID campaignId);

    Optional<EmailCampaignVariant> findByCampaignIdAndVariantCode(UUID campaignId, String variantCode);

    @Modifying
    @Query("UPDATE EmailCampaignVariant v SET v.sentCount = v.sentCount + 1 WHERE v.id = :variantId")
    void incrementSentCount(@Param("variantId") UUID variantId);

    @Modifying
    @Query("UPDATE EmailCampaignVariant v SET v.deliveredCount = v.deliveredCount + 1 WHERE v.id = :variantId")
    void incrementDeliveredCount(@Param("variantId") UUID variantId);

    @Modifying
    @Query("UPDATE EmailCampaignVariant v SET v.openedCount = v.openedCount + 1 WHERE v.id = :variantId")
    void incrementOpenedCount(@Param("variantId") UUID variantId);

    @Modifying
    @Query("UPDATE EmailCampaignVariant v SET v.clickedCount = v.clickedCount + 1 WHERE v.id = :variantId")
    void incrementClickedCount(@Param("variantId") UUID variantId);

    @Modifying
    @Query("UPDATE EmailCampaignVariant v SET v.isWinner = CASE WHEN v.id = :winnerId THEN true ELSE false END WHERE v.campaign.id = :campaignId")
    void setWinningVariant(@Param("campaignId") UUID campaignId, @Param("winnerId") UUID winnerId);
}
