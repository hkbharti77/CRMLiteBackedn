package com.chatcrmlite.backend.services.sms;

import com.chatcrmlite.backend.dtos.sms.SmsSendRequest;
import com.chatcrmlite.backend.dtos.sms.SmsSendResult;
import com.chatcrmlite.backend.models.sms.SmsCampaign;
import com.chatcrmlite.backend.models.sms.SmsCampaignRecipient;
import com.chatcrmlite.backend.models.sms.SmsTemplate;
import com.chatcrmlite.backend.repositories.SmsCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.SmsCampaignRepository;
import com.chatcrmlite.backend.repositories.SmsTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCampaignService {

    private final SmsCampaignRepository campaignRepository;
    private final SmsCampaignRecipientRepository recipientRepository;
    private final SmsTemplateRepository templateRepository;
    private final SmsService smsService;

    public List<SmsCampaign> getCampaignsByBusiness(String businessId) {
        return campaignRepository.findByBusinessId(businessId);
    }

    public Optional<SmsCampaign> getCampaignById(UUID id, String businessId) {
        return campaignRepository.findByIdAndBusinessId(id, businessId);
    }

    @Transactional
    public SmsCampaign createCampaign(String businessId, SmsCampaign campaign, List<String> phoneNumbers) {
        campaign.setBusinessId(businessId);
        campaign.setStatus("DRAFT");
        if (phoneNumbers != null) {
            campaign.setTotalRecipients(phoneNumbers.size());
        }
        SmsCampaign savedCampaign = campaignRepository.save(campaign);

        if (phoneNumbers != null) {
            for (String phone : phoneNumbers) {
                SmsCampaignRecipient recipient = new SmsCampaignRecipient();
                recipient.setCampaignId(savedCampaign.getId());
                recipient.setPhoneNumber(phone);
                recipient.setStatus("PENDING");
                recipientRepository.save(recipient);
            }
        }
        return savedCampaign;
    }

    @Async
    @Transactional
    public void executeCampaignAsync(UUID campaignId, String businessId) {
        Optional<SmsCampaign> campaignOpt = campaignRepository.findByIdAndBusinessId(campaignId, businessId);
        if (campaignOpt.isEmpty()) return;

        SmsCampaign campaign = campaignOpt.get();
        campaign.setStatus("PROCESSING");
        campaignRepository.save(campaign);

        Optional<SmsTemplate> templateOpt = campaign.getTemplateId() != null
                ? templateRepository.findById(campaign.getTemplateId())
                : Optional.empty();

        String templateContent = templateOpt.map(SmsTemplate::getContent).orElse("Hello from CRMLite!");
        String dltEntityId = templateOpt.map(SmsTemplate::getDltEntityId).orElse(null);
        String dltTemplateId = templateOpt.map(SmsTemplate::getDltTemplateId).orElse(null);
        String senderId = templateOpt.map(SmsTemplate::getSenderId).orElse(null);

        List<SmsCampaignRecipient> recipients = recipientRepository.findByCampaignId(campaignId);
        int delivered = 0;
        int failed = 0;

        for (SmsCampaignRecipient r : recipients) {
            try {
                SmsSendRequest req = SmsSendRequest.builder()
                        .businessId(businessId)
                        .contactId(r.getContactId())
                        .campaignId(campaignId)
                        .campaignRecipientId(r.getId())
                        .phoneNumber(r.getPhoneNumber())
                        .messageContent(templateContent)
                        .senderId(senderId)
                        .dltEntityId(dltEntityId)
                        .dltTemplateId(dltTemplateId)
                        .build();

                SmsSendResult result = smsService.sendSms(businessId, req);

                if (result.isSuccess()) {
                    r.setStatus("SENT");
                    r.setProviderMessageId(result.getProviderMessageId());
                    r.setSegments(result.getSegments());
                    r.setSentAt(LocalDateTime.now());
                    delivered++;
                } else {
                    r.setStatus("FAILED");
                    r.setErrorCode(result.getErrorCode());
                    r.setErrorMessage(result.getErrorMessage());
                    failed++;
                }
            } catch (Exception e) {
                log.error("Campaign execution error for recipient {}", r.getPhoneNumber(), e);
                r.setStatus("FAILED");
                r.setErrorMessage(e.getMessage());
                failed++;
            }
            recipientRepository.save(r);

            // Rate limiting sleep (100ms throttle)
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        campaign.setStatus("COMPLETED");
        campaign.setDeliveredCount(delivered);
        campaign.setFailedCount(failed);
        campaignRepository.save(campaign);
        log.info("Finished SMS Campaign {} with {} delivered, {} failed", campaignId, delivered, failed);
    }
}
