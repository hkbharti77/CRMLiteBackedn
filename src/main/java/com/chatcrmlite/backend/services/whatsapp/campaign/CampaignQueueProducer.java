package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignRecipient;
import com.chatcrmlite.backend.repositories.WhatsAppCampaignRecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignQueueProducer {

    private static final String CAMPAIGN_QUEUE_PREFIX = "campaign:queue:";
    private final StringRedisTemplate stringRedisTemplate;
    private final WhatsAppCampaignRecipientRepository recipientRepository;

    @Transactional
    public int queueCampaignRecipients(WhatsAppCampaign campaign) {
        String queueKey = CAMPAIGN_QUEUE_PREFIX + campaign.getId().toString();
        log.info("[CampaignQueueProducer] Enqueueing recipients for campaignId={} key={} isAbTest={}",
                campaign.getId(), queueKey, campaign.getIsAbTest());

        int totalQueued = 0;
        int page = 0;
        int pageSize = 200;

        List<WhatsAppCampaignRecipient> batch;
        do {
            if (Boolean.TRUE.equals(campaign.getIsAbTest())) {
                batch = recipientRepository.findByCampaignAndIsHoldoutAndStatus(
                        campaign,
                        false,
                        WhatsAppCampaignRecipient.RecipientStatus.PENDING,
                        PageRequest.of(page, pageSize)
                );
            } else {
                batch = recipientRepository.findByCampaignAndStatus(
                        campaign,
                        WhatsAppCampaignRecipient.RecipientStatus.PENDING,
                        PageRequest.of(page, pageSize)
                );
            }

            for (WhatsAppCampaignRecipient recipient : batch) {
                recipient.setStatus(WhatsAppCampaignRecipient.RecipientStatus.QUEUED);
                if (recipient.getAvailableAt() == null) {
                    recipient.setAvailableAt(java.time.LocalDateTime.now());
                }
                String tenantIdStr = campaign.getTenant() != null ? campaign.getTenant().getId().toString() : "global";
                int attempt = recipient.getAttemptCount() != null ? recipient.getAttemptCount() : 0;
                recipient.setIdempotencyKey(tenantIdStr + ":" + campaign.getId() + ":" + recipient.getId() + ":" + attempt);
                stringRedisTemplate.opsForList().rightPush(queueKey, recipient.getId().toString());
                totalQueued++;
            }

            recipientRepository.saveAll(batch);
            page++;
        } while (!batch.isEmpty());

        log.info("[CampaignQueueProducer] Enqueued {} recipients into Redis for campaignId={}", totalQueued, campaign.getId());
        return totalQueued;
    }

    @Transactional
    public int queueHoldoutRecipients(WhatsAppCampaign campaign, List<WhatsAppCampaignRecipient> holdouts) {
        if (holdouts == null || holdouts.isEmpty()) {
            return 0;
        }
        String queueKey = CAMPAIGN_QUEUE_PREFIX + campaign.getId().toString();
        int totalQueued = 0;

        for (WhatsAppCampaignRecipient recipient : holdouts) {
            recipient.setStatus(WhatsAppCampaignRecipient.RecipientStatus.QUEUED);
            if (recipient.getAvailableAt() == null) {
                recipient.setAvailableAt(java.time.LocalDateTime.now());
            }
            String tenantIdStr = campaign.getTenant() != null ? campaign.getTenant().getId().toString() : "global";
            int attempt = recipient.getAttemptCount() != null ? recipient.getAttemptCount() : 0;
            recipient.setIdempotencyKey(tenantIdStr + ":" + campaign.getId() + ":" + recipient.getId() + ":" + attempt);
            stringRedisTemplate.opsForList().rightPush(queueKey, recipient.getId().toString());
            totalQueued++;
        }

        recipientRepository.saveAll(holdouts);
        log.info("[CampaignQueueProducer] Enqueued {} winning holdout recipients into Redis for campaignId={}", totalQueued, campaign.getId());
        return totalQueued;
    }

    public String popTask(String campaignId) {
        String queueKey = CAMPAIGN_QUEUE_PREFIX + campaignId;
        return stringRedisTemplate.opsForList().leftPop(queueKey);
    }

    public Long getQueueSize(String campaignId) {
        String queueKey = CAMPAIGN_QUEUE_PREFIX + campaignId;
        return stringRedisTemplate.opsForList().size(queueKey);
    }
}
