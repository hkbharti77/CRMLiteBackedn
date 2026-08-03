package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.email.EmailCampaignAuditLog;
import com.chatcrmlite.backend.repositories.email.EmailCampaignAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCampaignAuditService {

    private final EmailCampaignAuditLogRepository auditLogRepository;

    @Transactional
    public void logAction(CustomEmail campaign, User actor, EmailCampaignAuditLog.Action action, String detailsJson) {
        try {
            EmailCampaignAuditLog logEntry = EmailCampaignAuditLog.builder()
                    .campaign(campaign)
                    .actorUser(actor)
                    .action(action)
                    .detailsJson(detailsJson)
                    .build();
            logEntry.setTenant(campaign.getOwner().getTenant());
            auditLogRepository.save(logEntry);
            log.info("[EmailCampaignAuditLog] campaignId={} action={} actor={}", campaign.getId(), action, actor != null ? actor.getEmail() : "SYSTEM");
        } catch (Exception e) {
            log.error("[EmailCampaignAuditLog] Failed to save audit log for campaignId={}: {}", campaign.getId(), e.getMessage());
        }
    }

    public List<EmailCampaignAuditLog> getAuditLogs(CustomEmail campaign) {
        return auditLogRepository.findByCampaignOrderByCreatedAtDesc(campaign);
    }
}
