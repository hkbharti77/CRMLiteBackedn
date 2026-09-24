package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.sms.SmsMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SmsMessageRepository extends JpaRepository<SmsMessage, UUID> {
    List<SmsMessage> findByBusinessId(String businessId);
    List<SmsMessage> findByBusinessIdAndContactId(String businessId, UUID contactId);
    List<SmsMessage> findByCampaignId(UUID campaignId);
    Optional<SmsMessage> findByProviderTypeAndProviderMessageId(String providerType, String providerMessageId);
}
