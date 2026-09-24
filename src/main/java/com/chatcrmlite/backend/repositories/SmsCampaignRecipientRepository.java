package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.sms.SmsCampaignRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SmsCampaignRecipientRepository extends JpaRepository<SmsCampaignRecipient, UUID> {
    List<SmsCampaignRecipient> findByCampaignId(UUID campaignId);
    Optional<SmsCampaignRecipient> findByCampaignIdAndPhoneNumber(UUID campaignId, String phoneNumber);
}
