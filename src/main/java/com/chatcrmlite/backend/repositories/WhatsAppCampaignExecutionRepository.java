package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WhatsAppCampaignExecutionRepository extends JpaRepository<WhatsAppCampaignExecution, UUID> {
    List<WhatsAppCampaignExecution> findByCampaignOrderByStartedAtDesc(WhatsAppCampaign campaign);
}
