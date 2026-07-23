package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignAuditLog;
import com.chatcrmlite.backend.repositories.WhatsAppCampaignAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignAuditService {

    private final WhatsAppCampaignAuditLogRepository auditLogRepository;

    @Transactional
    public void logAction(WhatsAppCampaign campaign, User actor, WhatsAppCampaignAuditLog.Action action, String detailsJson) {
        try {
            WhatsAppCampaignAuditLog logEntry = WhatsAppCampaignAuditLog.builder()
                    .campaign(campaign)
                    .actorUser(actor)
                    .action(action)
                    .detailsJson(detailsJson)
                    .build();
            logEntry.setTenant(campaign.getTenant());
            auditLogRepository.save(logEntry);
            log.info("[CampaignAuditLog] campaignId={} action={} actor={}", campaign.getId(), action, actor != null ? actor.getEmail() : "SYSTEM");
        } catch (Exception e) {
            log.error("[CampaignAuditLog] Failed to save audit log for campaignId={}: {}", campaign.getId(), e.getMessage());
        }
    }

    public List<WhatsAppCampaignAuditLog> getAuditLogs(WhatsAppCampaign campaign) {
        return auditLogRepository.findByCampaignOrderByCreatedAtDesc(campaign);
    }
}
