package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WhatsAppCampaignAnalyticsRepository extends JpaRepository<WhatsAppCampaignAnalytics, UUID> {
    Optional<WhatsAppCampaignAnalytics> findByCampaign(WhatsAppCampaign campaign);
}
