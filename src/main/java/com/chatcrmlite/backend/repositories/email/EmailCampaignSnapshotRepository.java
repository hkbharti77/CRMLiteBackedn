package com.chatcrmlite.backend.repositories.email;

import com.chatcrmlite.backend.models.email.EmailCampaignSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailCampaignSnapshotRepository extends JpaRepository<EmailCampaignSnapshot, UUID> {
    Optional<EmailCampaignSnapshot> findByCampaignId(UUID campaignId);
    Optional<EmailCampaignSnapshot> findByTenantIdAndCampaignId(UUID tenantId, UUID campaignId);
}
