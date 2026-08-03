package com.chatcrmlite.backend.repositories.email;

import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailCampaignRecipientRepository extends JpaRepository<EmailCampaignRecipient, UUID> {
    Optional<EmailCampaignRecipient> findByTrackingToken(String trackingToken);
    boolean existsByTenantIdAndCampaignIdAndEmail(UUID tenantId, UUID campaignId, String email);

    long countByCampaignId(UUID campaignId);
    long countByCampaignIdAndDeliveryStatusIn(UUID campaignId, java.util.Collection<EmailCampaignRecipient.DeliveryStatus> statuses);
    long countByCampaignIdAndFirstOpenedAtIsNotNull(UUID campaignId);
    long countByCampaignIdAndFirstClickedAtIsNotNull(UUID campaignId);
    long countByCampaignIdAndUnsubscribedAtIsNotNull(UUID campaignId);
    long countByCampaignIdAndDeliveryStatus(UUID campaignId, EmailCampaignRecipient.DeliveryStatus status);
    
    org.springframework.data.domain.Page<EmailCampaignRecipient> findByCampaignIdAndDeliveryStatus(UUID campaignId, EmailCampaignRecipient.DeliveryStatus status, org.springframework.data.domain.Pageable pageable);
}
