package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignRecipient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppCampaignRecipientRepository extends JpaRepository<WhatsAppCampaignRecipient, UUID> {
    List<WhatsAppCampaignRecipient> findByCampaignAndStatus(WhatsAppCampaign campaign, WhatsAppCampaignRecipient.RecipientStatus status, Pageable pageable);
    Optional<WhatsAppCampaignRecipient> findByWaMessageId(String waMessageId);
    boolean existsByCampaignIdAndContactId(UUID campaignId, UUID contactId);
    long countByCampaignAndStatus(WhatsAppCampaign campaign, WhatsAppCampaignRecipient.RecipientStatus status);
}
