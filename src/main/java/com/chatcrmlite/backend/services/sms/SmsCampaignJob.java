package com.chatcrmlite.backend.services.sms;

import com.chatcrmlite.backend.models.sms.SmsCampaign;
import com.chatcrmlite.backend.repositories.SmsCampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmsCampaignJob implements Job {

    private final SmsCampaignRepository campaignRepository;
    private final SmsCampaignService smsCampaignService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap dataMap = context.getMergedJobDataMap();
        String campaignIdStr = dataMap.getString("campaignId");
        String businessId = dataMap.getString("businessId");

        if (campaignIdStr == null || campaignIdStr.isBlank()) {
            log.warn("[SmsCampaignJob] Executed without campaignId in dataMap");
            return;
        }

        try {
            UUID campaignId = UUID.fromString(campaignIdStr);
            SmsCampaign campaign = campaignRepository.findById(campaignId).orElse(null);

            if (campaign != null && "SCHEDULED".equalsIgnoreCase(campaign.getStatus())) {
                log.info("[SmsCampaignJob] Triggering execution for scheduled SMS campaignId={}", campaignId);
                smsCampaignService.executeCampaignAsync(campaignId, businessId);
            } else {
                log.info("[SmsCampaignJob] Skipping execution for campaignId={}: status={}",
                        campaignId, (campaign != null ? campaign.getStatus() : "NOT_FOUND"));
            }
        } catch (Exception e) {
            log.error("[SmsCampaignJob] Failed to execute SMS campaign job for id={}: {}", campaignIdStr, e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
