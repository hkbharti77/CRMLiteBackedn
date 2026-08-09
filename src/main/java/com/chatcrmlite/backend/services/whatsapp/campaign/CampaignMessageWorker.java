package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppOutboundService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignMessageWorker {

    private static final int MAX_RETRIES = 3;

    private final CampaignQueueProducer queueProducer;
    private final WhatsAppCampaignRepository campaignRepository;
    private final WhatsAppCampaignRecipientRepository recipientRepository;
    private final WhatsAppConfigRepository whatsAppConfigRepository;
    private final WhatsAppClient whatsappClient;
    private final CampaignAnalyticsService analyticsService;
    private final CampaignAuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Scheduled task that polls for active RUNNING campaigns and dispatches messages
     * using Weighted Fair Scheduling (6:3:1 ratio for HIGH:MEDIUM:LOW) with anti-starvation aging.
     */
    @Scheduled(fixedDelay = 2000)
    public void processRunningCampaigns() {
        List<WhatsAppCampaign> runningCampaigns = campaignRepository.findAll().stream()
                .filter(c -> c.getStatus() == WhatsAppCampaign.Status.RUNNING)
                .sorted(Comparator.comparingInt(this::calculateEffectivePriority).reversed())
                .toList();

        for (WhatsAppCampaign campaign : runningCampaigns) {
            processCampaignBatch(campaign.getId());
        }
    }

    /**
     * Calculates priority rank for scheduling.
     * Includes aging boost: Campaigns waiting > 5 minutes receive +1 priority rank to prevent LOW queue starvation.
     */
    private int calculateEffectivePriority(WhatsAppCampaign campaign) {
        int baseRank = campaign.getPriorityRank() != null ? campaign.getPriorityRank() : campaign.getPriority().getRank();
        if (campaign.getCreatedAt() != null && campaign.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(5))) {
            return baseRank + 1; // Aging boost to prevent starvation
        }
        return baseRank;
    }

    @Async
    @Transactional
    public void processCampaignBatch(UUID campaignId) {
        WhatsAppCampaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null || campaign.getStatus() != WhatsAppCampaign.Status.RUNNING) {
            return;
        }

        String campaignIdStr = campaign.getId().toString();
        WhatsAppConfig config = whatsAppConfigRepository.findByUserId(campaign.getOwner().getId()).orElse(null);

        if (config == null || config.getAccessToken() == null || config.getPhoneNumberId() == null) {
            log.error("[CampaignWorker] WhatsAppConfig missing for tenant/owner={}", campaign.getOwner().getId());
            campaign.setStatus(WhatsAppCampaign.Status.FAILED);
            campaignRepository.save(campaign);
            auditService.logAction(campaign, null, WhatsAppCampaignAuditLog.Action.FAILED, "{\"reason\": \"WhatsAppConfig missing\"}");
            return;
        }

        WhatsAppTemplateSnapshot snapshot = campaign.getTemplateSnapshot();
        if (snapshot == null) {
            log.error("[CampaignWorker] TemplateSnapshot missing for campaignId={}", campaign.getId());
            campaign.setStatus(WhatsAppCampaign.Status.FAILED);
            campaignRepository.save(campaign);
            return;
        }

        // Determine rate delay per message (ms) based on WABA tier
        int delayMs = getRateLimitDelayMs(config.getMessagingTier());

        // Batch size based on campaign priority
        int batchSize = campaign.getPriority() == WhatsAppCampaign.Priority.HIGH ? 100 :
                        (campaign.getPriority() == WhatsAppCampaign.Priority.MEDIUM ? 50 : 20);
        int processedCount = 0;

        while (processedCount < batchSize) {
            String recipientIdStr = queueProducer.popTask(campaignIdStr);
            if (recipientIdStr == null) {
                long remainingUnfinished = recipientRepository.countByCampaignAndStatusIn(campaign, List.of(
                    WhatsAppCampaignRecipient.RecipientStatus.QUEUED,
                    WhatsAppCampaignRecipient.RecipientStatus.PENDING
                ));
                if (remainingUnfinished == 0) {
                    campaign.setStatus(WhatsAppCampaign.Status.COMPLETED);
                    campaign.setCompletedAt(LocalDateTime.now());
                    campaignRepository.save(campaign);
                    analyticsService.updateAnalyticsRollup(campaign);
                    auditService.logAction(campaign, null, WhatsAppCampaignAuditLog.Action.COMPLETED, "{\"totalProcessed\": " + processedCount + "}");
                    log.info("[CampaignWorker] Campaign broadcast completed for campaignId={}", campaign.getId());
                }
                break;
            }

            try {
                UUID recipientId = UUID.fromString(recipientIdStr);
                Optional<WhatsAppCampaignRecipient> optRecipient = recipientRepository.findById(recipientId);
                if (optRecipient.isPresent()) {
                    WhatsAppCampaignRecipient recipient = optRecipient.get();

                    // Check exponential backoff nextAttemptAt if set
                    if (recipient.getNextAttemptAt() != null && recipient.getNextAttemptAt().isAfter(LocalDateTime.now())) {
                        log.debug("[CampaignWorker] Skipping recipient {} until backoff time {}", recipient.getId(), recipient.getNextAttemptAt());
                        continue;
                    }

                    sendRecipientMessage(recipient, snapshot, config);
                }
            } catch (Exception e) {
                log.error("[CampaignWorker] Error processing recipient task task={}: {}", recipientIdStr, e.getMessage());
            }

            processedCount++;
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void sendRecipientMessage(WhatsAppCampaignRecipient recipient, WhatsAppTemplateSnapshot snapshot, WhatsAppConfig config) {
        int attempt = recipient.getAttemptCount() != null ? recipient.getAttemptCount() : (recipient.getRetryCount() != null ? recipient.getRetryCount() : 0);
        recipient.setAttemptCount(attempt + 1);

        try {
            List<String> parameters = parseParameters(recipient.getResolvedVariablesJson());

            // Build Meta template payload parameters or send text fallback
            String metaMessageId = whatsappClient.sendMessage(
                    recipient.getPhoneNumber(),
                    buildRenderedBody(snapshot.getBodyText(), parameters),
                    config.getAccessToken(),
                    config.getPhoneNumberId()
            );

            recipient.setStatus(WhatsAppCampaignRecipient.RecipientStatus.SENT);
            recipient.setWaMessageId(metaMessageId);
            recipient.setSentAt(LocalDateTime.now());
            recipient.setNextAttemptAt(null);
            recipientRepository.save(recipient);

        } catch (Exception e) {
            log.warn("[CampaignWorker] Failed sending message to recipientId={} attempt={}: {}", recipient.getId(), attempt + 1, e.getMessage());
            recipient.setRetryCount(attempt + 1);
            recipient.setErrorMessage(e.getMessage());

            if (attempt + 1 < MAX_RETRIES) {
                // Calculate exponential backoff delay (Attempt 1: +2s, Attempt 2: +5s, Attempt 3: +15s)
                long backoffSeconds = (attempt + 1 == 1) ? 2 : ((attempt + 1 == 2) ? 5 : 15);
                recipient.setNextAttemptAt(LocalDateTime.now().plusSeconds(backoffSeconds));
                recipientRepository.save(recipient);
                // Re-queue task into Redis for deferred retry
                queueProducer.queueCampaignRecipients(recipient.getCampaign());
            } else {
                // Dead-Letter State
                recipient.setStatus(WhatsAppCampaignRecipient.RecipientStatus.FAILED);
                recipient.setErrorMessage("Max retries exceeded (" + (attempt + 1) + "): " + e.getMessage());
                recipientRepository.save(recipient);
            }
        }
    }

    private int getRateLimitDelayMs(String tier) {
        if (tier == null) return 1000; // 1 msg/sec default
        switch (tier.toUpperCase()) {
            case "TIER_100K":
            case "TIER_3":
                return 50; // 20 msg/sec
            case "TIER_10K":
            case "TIER_2":
                return 200; // 5 msg/sec
            case "TIER_1K":
            case "TIER_1":
            default:
                return 1000; // 1 msg/sec
        }
    }

    private List<String> parseParameters(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String buildRenderedBody(String templateBody, List<String> parameters) {
        if (templateBody == null) return "";
        String result = templateBody;
        for (int i = 0; i < parameters.size(); i++) {
            String placeholder = "{{" + (i + 1) + "}}";
            result = result.replace(placeholder, parameters.get(i));
        }
        return result;
    }
}
