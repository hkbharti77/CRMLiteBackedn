package com.chatcrmlite.backend.services.sms;

import com.chatcrmlite.backend.dtos.sms.SmsSendRequest;
import com.chatcrmlite.backend.dtos.sms.SmsSendResult;
import com.chatcrmlite.backend.models.sms.SmsMessage;
import com.chatcrmlite.backend.models.sms.SmsProvider;
import com.chatcrmlite.backend.models.sms.SmsProviderCredentials;
import com.chatcrmlite.backend.repositories.SmsMessageRepository;
import com.chatcrmlite.backend.repositories.SmsProviderRepository;
import com.chatcrmlite.backend.repositories.SmsSuppressionRepository;
import com.chatcrmlite.backend.utils.SmsSegmentCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsService {

    private final SmsProviderRepository providerRepository;
    private final SmsMessageRepository messageRepository;
    private final SmsSuppressionRepository suppressionRepository;
    private final SmsProviderResolver providerResolver;
    private final ObjectMapper objectMapper;

    @Transactional
    public SmsSendResult sendSms(String businessId, SmsSendRequest request) {
        request.setBusinessId(businessId);

        // 1. Suppression / DND Opt-Out Check
        if (suppressionRepository.existsByBusinessIdAndPhoneNumber(businessId, request.getPhoneNumber())) {
            log.warn("Blocked SMS dispatch to suppressed number {} for business {}", request.getPhoneNumber(), businessId);
            return SmsSendResult.builder()
                    .success(false)
                    .provider("NONE")
                    .errorCode("CONTACT_SUPPRESSED")
                    .errorMessage("Phone number is on the DND / Opt-out suppression list.")
                    .build();
        }

        // 2. Resolve Active Default Provider
        Optional<SmsProvider> providerOpt = providerRepository.findByBusinessIdAndIsDefaultTrue(businessId);
        if (providerOpt.isEmpty()) {
            return SmsSendResult.builder()
                    .success(false)
                    .provider("NONE")
                    .errorCode("NO_PROVIDER_CONFIGURED")
                    .errorMessage("No default SMS provider configured for business " + businessId)
                    .build();
        }

        SmsProvider providerEntity = providerOpt.get();
        Optional<SmsSenderProvider> senderOpt = providerResolver.resolve(providerEntity.getProviderType());
        if (senderOpt.isEmpty()) {
            return SmsSendResult.builder()
                    .success(false)
                    .provider(providerEntity.getProviderType())
                    .errorCode("PROVIDER_NOT_IMPLEMENTED")
                    .errorMessage("Provider engine for " + providerEntity.getProviderType() + " is not loaded.")
                    .build();
        }

        // 3. Decrypt and Parse Typed Credentials
        SmsProviderCredentials credentials;
        try {
            credentials = objectMapper.readValue(providerEntity.getCredentialsPayload(), SmsProviderCredentials.class);
            if (credentials.getSenderId() == null || credentials.getSenderId().isBlank()) {
                credentials.setSenderId(providerEntity.getSenderId());
            }
        } catch (Exception e) {
            log.error("Failed to parse provider credentials payload for business {}", businessId, e);
            return SmsSendResult.builder()
                    .success(false)
                    .provider(providerEntity.getProviderType())
                    .errorCode("INVALID_CREDENTIALS_PAYLOAD")
                    .errorMessage("Failed to parse provider credentials.")
                    .build();
        }

        // 4. Calculate Segments & Encoding
        SmsSegmentCalculator.SegmentInfo segInfo = SmsSegmentCalculator.calculateSegments(request.getMessageContent());

        // 5. Initial Persist of Outbound Message in QUEUED state
        SmsMessage smsMsg = new SmsMessage();
        smsMsg.setBusinessId(businessId);
        smsMsg.setContactId(request.getContactId());
        smsMsg.setCampaignId(request.getCampaignId());
        smsMsg.setCampaignRecipientId(request.getCampaignRecipientId());
        smsMsg.setProviderId(providerEntity.getId());
        smsMsg.setProviderType(providerEntity.getProviderType());
        smsMsg.setDirection("OUTGOING");
        smsMsg.setPhoneNumber(request.getPhoneNumber());
        smsMsg.setContent(request.getMessageContent());
        smsMsg.setEncoding(segInfo.getEncoding());
        smsMsg.setSegments(segInfo.getSegments());
        smsMsg.setDeliveryStatus("QUEUED");
        smsMsg = messageRepository.save(smsMsg);

        // 6. Execute Dispatch via Provider Adapter
        SmsSenderProvider senderProvider = senderOpt.get();
        SmsSendResult result = senderProvider.sendSms(request, credentials);

        // 7. Update Message Ledger Record
        if (result.isSuccess()) {
            smsMsg.setProviderMessageId(result.getProviderMessageId());
            smsMsg.setDeliveryStatus("SENT");
        } else {
            smsMsg.setDeliveryStatus("FAILED");
            smsMsg.setErrorCode(result.getErrorCode());
            smsMsg.setErrorMessage(result.getErrorMessage());
        }

        messageRepository.save(smsMsg);
        return result;
    }
}
