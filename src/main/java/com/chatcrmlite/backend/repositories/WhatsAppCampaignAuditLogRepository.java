package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WhatsAppCampaignAuditLogRepository extends JpaRepository<WhatsAppCampaignAuditLog, UUID> {
    List<WhatsAppCampaignAuditLog> findByCampaignOrderByCreatedAtDesc(WhatsAppCampaign campaign);
}
