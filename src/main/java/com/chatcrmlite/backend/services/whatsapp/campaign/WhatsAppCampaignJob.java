package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.repositories.WhatsAppCampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppCampaignJob implements Job {

    private final WhatsAppCampaignRepository campaignRepository;
    private final CampaignAuditService auditService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap dataMap = context.getMergedJobDataMap();
        String campaignIdStr = dataMap.getString("campaignId");
        if (campaignIdStr == null || campaignIdStr.isBlank()) {
            log.warn("[WhatsAppCampaignJob] Executed without campaignId");
            return;
        }

        try {
            UUID campaignId = UUID.fromString(campaignIdStr);
            WhatsAppCampaign campaign = campaignRepository.findById(campaignId).orElse(null);
            if (campaign != null && campaign.getStatus() == WhatsAppCampaign.Status.SCHEDULED) {
                campaign.setStatus(WhatsAppCampaign.Status.RUNNING);
                campaign.setStartedAt(LocalDateTime.now());
                campaignRepository.save(campaign);
                auditService.logAction(campaign, null, com.chatcrmlite.backend.models.WhatsAppCampaignAuditLog.Action.STARTED, "{\"trigger\": \"Quartz Scheduler\"}");
                log.info("[WhatsAppCampaignJob] Successfully triggered campaignId={} to RUNNING", campaignId);
            }
        } catch (Exception e) {
            log.error("[WhatsAppCampaignJob] Failed executing job for campaignId={}: {}", campaignIdStr, e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
