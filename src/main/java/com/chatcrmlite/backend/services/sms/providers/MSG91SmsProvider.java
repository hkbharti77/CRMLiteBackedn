package com.chatcrmlite.backend.services.sms.providers;

import com.chatcrmlite.backend.dtos.sms.SmsSendRequest;
import com.chatcrmlite.backend.dtos.sms.SmsSendResult;
import com.chatcrmlite.backend.models.sms.SmsProviderCapabilities;
import com.chatcrmlite.backend.models.sms.SmsProviderCredentials;
import com.chatcrmlite.backend.services.sms.SmsSenderProvider;
import com.chatcrmlite.backend.utils.SmsSegmentCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class MSG91SmsProvider implements SmsSenderProvider {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderType() {
        return "MSG91";
    }

    @Override
    public SmsProviderCapabilities getCapabilities() {
        return SmsProviderCapabilities.builder()
                .inboundSms(true)
                .deliveryReports(true)
                .unicode(true)
                .scheduling(true)
                .senderIdSupported(true)
                .templateMessagingRequired(true)
                .indiaDltRequired(true)
                .build();
    }

    @Override
    public SmsSendResult sendSms(SmsSendRequest request, SmsProviderCredentials credentials) {
        String authKey = credentials.getApiKey();
        String senderId = (request.getSenderId() != null && !request.getSenderId().isBlank())
                ? request.getSenderId()
                : credentials.getSenderId();

        if (authKey == null || authKey.isBlank()) {
            return SmsSendResult.builder()
                    .success(false)
                    .provider(getProviderType())
                    .errorCode("MISSING_CREDENTIALS")
                    .errorMessage("MSG91 Auth Key (apiKey) is missing.")
                    .build();
        }

        SmsSegmentCalculator.SegmentInfo segmentInfo = SmsSegmentCalculator.calculateSegments(request.getMessageContent());

        try {
            String url = "https://control.msg91.com/api/v5/flow/";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("authkey", authKey);

            Map<String, Object> bodyMap = new HashMap<>();
            bodyMap.put("template_id", request.getDltTemplateId());
            bodyMap.put("sender", senderId);

            Map<String, Object> recipientObj = new HashMap<>();
            recipientObj.put("mobiles", request.getPhoneNumber().replaceAll("[^0-9]", ""));

            if (request.getTemplateVariables() != null) {
                recipientObj.putAll(request.getTemplateVariables());
            }

            bodyMap.put("recipients", new Object[]{recipientObj});

            String jsonPayload = objectMapper.writeValueAsString(bodyMap);
            HttpEntity<String> httpRequest = new HttpEntity<>(jsonPayload, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, httpRequest, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> respMap = objectMapper.readValue(response.getBody(), Map.class);
                String messageId = (String) respMap.get("message_id");
                String type = (String) respMap.get("type");

                boolean isSuccess = "success".equalsIgnoreCase(type) || messageId != null;

                Map<String, Object> sanitizedMetadata = new HashMap<>();
                sanitizedMetadata.put("message_id", messageId);
                sanitizedMetadata.put("type", type);

                return SmsSendResult.builder()
                        .success(isSuccess)
                        .providerMessageId(messageId != null ? messageId : "MSG91_" + System.currentTimeMillis())
                        .providerRequestId(messageId)
                        .provider(getProviderType())
                        .segments(segmentInfo.getSegments())
                        .metadata(sanitizedMetadata)
                        .build();
            } else {
                return SmsSendResult.builder()
                        .success(false)
                        .provider(getProviderType())
                        .errorCode("HTTP_" + response.getStatusCode().value())
                        .errorMessage("MSG91 API returned status " + response.getStatusCode())
                        .build();
            }
        } catch (Exception e) {
            log.error("MSG91 SMS send error for phone={}: {}", request.getPhoneNumber(), e.getMessage(), e);
            return SmsSendResult.builder()
                    .success(false)
                    .provider(getProviderType())
                    .errorCode("PROVIDER_ERROR")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signatureHeader, String providerSecret) {
        // MSG91 secret verification pattern
        if (providerSecret == null || signatureHeader == null) return true;
        return providerSecret.equals(signatureHeader);
    }
}
