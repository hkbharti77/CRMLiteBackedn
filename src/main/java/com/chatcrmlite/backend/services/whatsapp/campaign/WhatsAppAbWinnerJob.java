package com.chatcrmlite.backend.services.whatsapp.campaign;

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
public class WhatsAppAbWinnerJob implements Job {

    private final WhatsAppAbTestingService abTestingService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap dataMap = context.getMergedJobDataMap();
        String campaignIdStr = dataMap.getString("campaignId");
        if (campaignIdStr == null || campaignIdStr.isBlank()) {
            log.warn("[WhatsAppAbWinnerJob] Executed without campaignId in JobDataMap");
            return;
        }

        try {
            UUID campaignId = UUID.fromString(campaignIdStr);
            log.info("🏆 [WhatsAppAbWinnerJob] Quartz scheduler triggered automatic winner evaluation for campaignId={}", campaignId);
            abTestingService.evaluateAndPromoteWinner(campaignId, null, null);
        } catch (Exception e) {
            log.error("❌ [WhatsAppAbWinnerJob] Failed executing auto-winner evaluation for campaignId={}: {}", campaignIdStr, e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
