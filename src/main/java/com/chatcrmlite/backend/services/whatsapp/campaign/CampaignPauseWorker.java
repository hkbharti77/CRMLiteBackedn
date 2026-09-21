package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.models.CampaignPauseOutbox;
import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignAuditLog;
import com.chatcrmlite.backend.repositories.CampaignPauseOutboxRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignPauseWorker {

    private final CampaignPauseOutboxRepository outboxRepository;
    private final WhatsAppCampaignRepository campaignRepository;
    private final CampaignAuditService auditService;

    @Scheduled(fixedDelay = 5000)
    public void processOutbox() {
        List<CampaignPauseOutbox> pending = outboxRepository.findTop50ByStatusOrderByCreatedAtAsc("PENDING");
        if (pending.isEmpty()) return;

        for (CampaignPauseOutbox item : pending) {
            try {
                processSinglePause(item);
            } catch (Exception e) {
                log.error("[CampaignPauseWorker] Error processing outbox item {}: {}", item.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void processSinglePause(CampaignPauseOutbox item) {
        UUID tenantId = item.getTenant().getId();
        UUID templateId = item.getTemplate().getId();
        String metaTemplateId = item.getTemplate().getMetaTemplateId() != null ? item.getTemplate().getMetaTemplateId() : "";

        List<WhatsAppCampaign> activeCampaigns = campaignRepository.findAllByTenantIdAndTemplateIdAndStatusIn(
                tenantId,
                templateId,
                metaTemplateId,
                List.of(WhatsAppCampaign.Status.RUNNING, WhatsAppCampaign.Status.QUEUED, WhatsAppCampaign.Status.SCHEDULED)
        );

        int pausedCount = 0;
        for (WhatsAppCampaign campaign : activeCampaigns) {
            if (campaign.getStatus() == WhatsAppCampaign.Status.PAUSED) continue; // Idempotent check

            campaign.setStatus(WhatsAppCampaign.Status.PAUSED);
            campaign.setPauseReason("META_TEMPLATE_PAUSED");
            campaign.setPausedAt(Instant.now());
            campaign.setPausedBy("SYSTEM");
            campaign.setPauseSourceEventId(item.getSourceEventId());
            campaignRepository.save(campaign);

            auditService.logAction(
                    campaign,
                    null,
                    WhatsAppCampaignAuditLog.Action.PAUSED,
                    String.format("{\"reason\": \"META_TEMPLATE_PAUSED\", \"sourceEventId\": \"%s\"}", item.getSourceEventId())
            );
            pausedCount++;
        }

        item.setStatus("PROCESSED");
        item.setProcessedAt(Instant.now());
        outboxRepository.save(item);

        log.info("🛡️ [CampaignPauseWorker] Processed outbox {}: Auto-paused {} active campaigns for template '{}'",
                item.getId(), pausedCount, item.getTemplate().getName());
    }
}
