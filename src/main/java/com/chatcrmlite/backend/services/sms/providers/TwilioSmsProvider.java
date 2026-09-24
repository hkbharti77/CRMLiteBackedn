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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class TwilioSmsProvider implements SmsSenderProvider {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderType() {
        return "TWILIO";
    }

    @Override
    public SmsProviderCapabilities getCapabilities() {
        return SmsProviderCapabilities.builder()
                .inboundSms(true)
                .deliveryReports(true)
                .unicode(true)
                .scheduling(true)
                .senderIdSupported(true)
                .templateMessagingRequired(false)
                .indiaDltRequired(false)
                .build();
    }

    @Override
    public SmsSendResult sendSms(SmsSendRequest request, SmsProviderCredentials credentials) {
        String accountSid = credentials.getAccountSid();
        String authToken = credentials.getAuthToken();
        String fromNumber = (request.getSenderId() != null && !request.getSenderId().isBlank())
                ? request.getSenderId()
                : credentials.getSenderId();

        if (accountSid == null || authToken == null) {
            return SmsSendResult.builder()
                    .success(false)
                    .provider(getProviderType())
                    .errorCode("MISSING_CREDENTIALS")
                    .errorMessage("Twilio Account SID or Auth Token is missing.")
                    .build();
        }

        SmsSegmentCalculator.SegmentInfo segmentInfo = SmsSegmentCalculator.calculateSegments(request.getMessageContent());

        try {
            String url = String.format("https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json", accountSid);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setBasicAuth(accountSid, authToken);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("To", request.getPhoneNumber());
            body.add("From", fromNumber);
            body.add("Body", request.getMessageContent());

            HttpEntity<MultiValueMap<String, String>> httpRequest = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, httpRequest, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> respMap = objectMapper.readValue(response.getBody(), Map.class);
                String sid = (String) respMap.get("sid");
                String status = (String) respMap.get("status");

                Map<String, Object> sanitizedMetadata = new HashMap<>();
                sanitizedMetadata.put("sid", sid);
                sanitizedMetadata.put("status", status);
                sanitizedMetadata.put("num_segments", respMap.get("num_segments"));

                return SmsSendResult.builder()
                        .success(true)
                        .providerMessageId(sid)
                        .providerRequestId(sid)
                        .provider(getProviderType())
                        .segments(segmentInfo.getSegments())
                        .metadata(sanitizedMetadata)
                        .build();
            } else {
                return SmsSendResult.builder()
                        .success(false)
                        .provider(getProviderType())
                        .errorCode("HTTP_" + response.getStatusCode().value())
                        .errorMessage("Twilio API returned status " + response.getStatusCode())
                        .build();
            }
        } catch (Exception e) {
            log.error("Twilio SMS send error for phone={}: {}", request.getPhoneNumber(), e.getMessage(), e);
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
        if (signatureHeader == null || providerSecret == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(providerSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = Base64.getEncoder().encodeToString(hmacBytes);
            return expectedSignature.equals(signatureHeader);
        } catch (Exception e) {
            log.error("Error verifying Twilio webhook signature", e);
            return false;
        }
    }
}
