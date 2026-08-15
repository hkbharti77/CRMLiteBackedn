package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.repositories.CustomEmailRepository;
import com.chatcrmlite.backend.services.CustomEmailService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.time.LocalDateTime;

@Slf4j
@Component
@DisallowConcurrentExecution
public class EmailCampaignJob extends QuartzJobBean {

    @Autowired
    private CustomEmailRepository customEmailRepository;

    @Autowired
    private CustomEmailService customEmailService;
    
    @Autowired
    private EmailCampaignStateService stateService;

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        String campaignIdStr = context.getMergedJobDataMap().getString("campaignId");
        if (campaignIdStr == null) {
            log.error("[EmailCampaignJob] No campaignId provided in JobDataMap");
            return;
        }

        UUID campaignId = UUID.fromString(campaignIdStr);
        CustomEmail campaign = customEmailRepository.findById(campaignId).orElse(null);

        if (campaign == null) {
            log.warn("[EmailCampaignJob] Campaign {} not found. Aborting job.", campaignId);
            return;
        }
        
        if (campaign.getStatus() == CustomEmail.EmailStatus.SCHEDULED) {
            try {
                log.info("[EmailCampaignJob] Starting scheduled campaign {}", campaignId);
                stateService.transitionState(campaign, CustomEmail.EmailStatus.SENDING, null);
                
                // Process the campaign by initiating the dispatch process
                customEmailService.startCampaignExecution(campaignId);
            } catch (Exception e) {
                log.error("[EmailCampaignJob] Error executing campaign {}", campaignId, e);
                try {
                    stateService.transitionState(campaign, CustomEmail.EmailStatus.FAILED, null);
                } catch (Exception ex) {
                    log.error("[EmailCampaignJob] Failed to set campaign {} to FAILED state", campaignId, ex);
                }
            }
        } else {
            log.info("[EmailCampaignJob] Campaign {} is in state {}, skipping execution", campaignId, campaign.getStatus());
        }
    }
}
