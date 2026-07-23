package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignAnalytics;
import com.chatcrmlite.backend.models.WhatsAppCampaignRecipient;
import com.chatcrmlite.backend.repositories.WhatsAppCampaignAnalyticsRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCampaignRecipientRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignAnalyticsService {

    private final WhatsAppCampaignRecipientRepository recipientRepository;
    private final WhatsAppCampaignAnalyticsRepository analyticsRepository;

    @Transactional
    public void processWebhookStatus(String waMessageId, String metaStatus, String errorMessage) {
        if (waMessageId == null || waMessageId.isBlank()) return;

        Optional<WhatsAppCampaignRecipient> optRecipient = recipientRepository.findByWaMessageId(waMessageId);
        if (optRecipient.isEmpty()) {
            return; // Message is not part of a campaign broadcast
        }

        WhatsAppCampaignRecipient recipient = optRecipient.get();
        WhatsAppCampaign campaign = recipient.getCampaign();
        LocalDateTime now = LocalDateTime.now();

        switch (metaStatus.toLowerCase()) {
            case "sent":
                recipient.setStatus(WhatsAppCampaignRecipient.RecipientStatus.SENT);
                recipient.setSentAt(now);
                break;
            case "delivered":
                recipient.setStatus(WhatsAppCampaignRecipient.RecipientStatus.DELIVERED);
                recipient.setDeliveredAt(now);
                break;
            case "read":
                recipient.setStatus(WhatsAppCampaignRecipient.RecipientStatus.READ);
                recipient.setReadAt(now);
                break;
            case "failed":
                recipient.setStatus(WhatsAppCampaignRecipient.RecipientStatus.FAILED);
                recipient.setErrorMessage(errorMessage);
                break;
            default:
                break;
        }

        recipientRepository.save(recipient);
        updateAnalyticsRollup(campaign);
    }

    @Transactional
    public WhatsAppCampaignAnalytics updateAnalyticsRollup(WhatsAppCampaign campaign) {
        WhatsAppCampaignAnalytics analytics = analyticsRepository.findByCampaign(campaign)
                .orElseGet(() -> WhatsAppCampaignAnalytics.builder()
                        .campaign(campaign)
                        .build());

        analytics.setTenant(campaign.getTenant());
        analytics.setTotalSent((int) recipientRepository.countByCampaignAndStatus(campaign, WhatsAppCampaignRecipient.RecipientStatus.SENT));
        analytics.setTotalDelivered((int) recipientRepository.countByCampaignAndStatus(campaign, WhatsAppCampaignRecipient.RecipientStatus.DELIVERED));
        analytics.setTotalRead((int) recipientRepository.countByCampaignAndStatus(campaign, WhatsAppCampaignRecipient.RecipientStatus.READ));
        analytics.setTotalFailed((int) recipientRepository.countByCampaignAndStatus(campaign, WhatsAppCampaignRecipient.RecipientStatus.FAILED));
        analytics.setTotalSkippedRecipients((int) recipientRepository.countByCampaignAndStatus(campaign, WhatsAppCampaignRecipient.RecipientStatus.SKIPPED));
        analytics.setTotalQueued((int) recipientRepository.countByCampaignAndStatus(campaign, WhatsAppCampaignRecipient.RecipientStatus.QUEUED));
        analytics.setLastUpdatedAt(LocalDateTime.now());

        return analyticsRepository.save(analytics);
    }
}
