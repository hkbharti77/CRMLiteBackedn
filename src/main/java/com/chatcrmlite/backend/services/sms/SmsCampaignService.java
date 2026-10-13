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
import com.chatcrmlite.backend.services.DistributedSchedulerService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCampaignService {

    private final SmsCampaignRepository campaignRepository;
    private final SmsCampaignRecipientRepository recipientRepository;
    private final SmsTemplateRepository templateRepository;
    private final SmsService smsService;
    private final DistributedSchedulerService distributedSchedulerService;

    public List<SmsCampaign> getCampaignsByBusiness(String businessId) {
        UUID tenantId = tryParseUuid(businessId);
        if (tenantId != null) {
            List<SmsCampaign> list = campaignRepository.findByTenant_Id(tenantId);
            if (!list.isEmpty()) {
                return list;
            }
        }
        return campaignRepository.findByBusinessId(businessId);
    }

    public Optional<SmsCampaign> getCampaignById(UUID id, String businessId) {
        UUID tenantId = tryParseUuid(businessId);
        if (tenantId != null) {
            Optional<SmsCampaign> opt = campaignRepository.findByIdAndTenant_Id(id, tenantId);
            if (opt.isPresent()) {
                return opt;
            }
        }
        return campaignRepository.findByIdAndBusinessId(id, businessId);
    }

    @Transactional
    public SmsCampaign createCampaign(String businessId, SmsCampaign campaign, List<String> phoneNumbers) {
        campaign.setBusinessId(businessId);
        UUID tenantId = tryParseUuid(businessId);
        if (tenantId != null) {
            campaign.setTenantId(tenantId);
        }
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

    @Transactional
    public SmsCampaign scheduleCampaign(UUID campaignId, LocalDateTime scheduleTime, String businessId) {
        Optional<SmsCampaign> campaignOpt = getCampaignById(campaignId, businessId);
        if (campaignOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SMS Campaign not found or access denied");
        }

        SmsCampaign campaign = campaignOpt.get();
        String currentStatus = campaign.getStatus() != null ? campaign.getStatus().toUpperCase() : "DRAFT";
        if ("PROCESSING".equals(currentStatus) || "COMPLETED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot schedule a campaign that is " + currentStatus);
        }

        if (scheduleTime == null || scheduleTime.isBefore(LocalDateTime.now().plusSeconds(60))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Schedule time must be at least 1 minute in the future");
        }

        // Deschedule previous Quartz job if re-scheduling
        distributedSchedulerService.cancelJob("sms-campaign-job-" + campaignId);

        campaign.setScheduledAt(scheduleTime);
        campaign.setStatus("SCHEDULED");
        SmsCampaign saved = campaignRepository.save(campaign);

        Date startDate = Date.from(scheduleTime.atZone(ZoneId.systemDefault()).toInstant());
        distributedSchedulerService.scheduleOneTimeJob(
                "sms-campaign-job-" + campaignId,
                SmsCampaignJob.class,
                startDate,
                Map.of("campaignId", campaignId.toString(), "businessId", businessId)
        );

        log.info("Scheduled SMS Campaign {} for {} via Quartz", campaignId, scheduleTime);
        return saved;
    }

    @Transactional
    public SmsCampaign cancelCampaign(UUID campaignId, String businessId) {
        Optional<SmsCampaign> campaignOpt = getCampaignById(campaignId, businessId);
        if (campaignOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SMS Campaign not found or access denied");
        }

        SmsCampaign campaign = campaignOpt.get();
        String currentStatus = campaign.getStatus() != null ? campaign.getStatus().toUpperCase() : "DRAFT";
        if ("PROCESSING".equals(currentStatus) || "COMPLETED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot cancel a campaign that is " + currentStatus);
        }

        // Cancel Quartz trigger if scheduled
        distributedSchedulerService.cancelJob("sms-campaign-job-" + campaignId);

        campaign.setStatus("CANCELLED");
        campaign.setCancelledAt(LocalDateTime.now());
        SmsCampaign saved = campaignRepository.save(campaign);
        log.info("Cancelled SMS Campaign {} and unscheduled Quartz trigger", campaignId);
        return saved;
    }

    @Async
    @Transactional
    public void executeCampaignAsync(UUID campaignId, String businessId) {
        Optional<SmsCampaign> campaignOpt = getCampaignById(campaignId, businessId);
        if (campaignOpt.isEmpty()) {
            log.warn("Cannot execute campaign {}: Not found or unauthorized for businessId {}", campaignId, businessId);
            return;
        }

        SmsCampaign campaign = campaignOpt.get();
        campaign.setStatus("PROCESSING");
        campaign.setStartedAt(LocalDateTime.now());
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
                    int currentRetries = r.getRetryCount() != null ? r.getRetryCount() : 0;
                    if (currentRetries < 3) {
                        r.setRetryCount(currentRetries + 1);
                        int delaySec = currentRetries == 0 ? 5 : (currentRetries == 1 ? 30 : 120);
                        r.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySec));
                        r.setStatus("RETRYING");
                        log.warn("SMS send transient error for {}, scheduled retry {} in {}s",
                                r.getPhoneNumber(), r.getRetryCount(), delaySec);
                    } else {
                        r.setStatus("FAILED");
                        failed++;
                    }
                    r.setErrorCode(result.getErrorCode());
                    r.setErrorMessage(result.getErrorMessage());
                }
            } catch (Exception e) {
                log.error("Campaign execution error for recipient {}", r.getPhoneNumber(), e);
                int currentRetries = r.getRetryCount() != null ? r.getRetryCount() : 0;
                if (currentRetries < 3) {
                    r.setRetryCount(currentRetries + 1);
                    int delaySec = currentRetries == 0 ? 5 : (currentRetries == 1 ? 30 : 120);
                    r.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySec));
                    r.setStatus("RETRYING");
                } else {
                    r.setStatus("FAILED");
                    failed++;
                }
                r.setErrorMessage(e.getMessage());
            }
            recipientRepository.save(r);

            // Rate limiting sleep (100ms throttle)
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        campaign.setStatus("COMPLETED");
        campaign.setCompletedAt(LocalDateTime.now());
        campaign.setDeliveredCount(delivered);
        campaign.setFailedCount(failed);
        campaignRepository.save(campaign);
        log.info("Finished SMS Campaign {} with {} delivered, {} failed", campaignId, delivered, failed);
    }

    private UUID tryParseUuid(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            return UUID.fromString(val.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
