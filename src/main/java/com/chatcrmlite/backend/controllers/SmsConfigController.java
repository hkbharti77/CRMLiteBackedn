package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dtos.sms.SmsSendRequest;
import com.chatcrmlite.backend.dtos.sms.SmsSendResult;
import com.chatcrmlite.backend.models.sms.SmsProvider;
import com.chatcrmlite.backend.models.sms.SmsProviderCapabilities;
import com.chatcrmlite.backend.models.sms.SmsProviderCredentials;
import com.chatcrmlite.backend.repositories.SmsProviderRepository;
import com.chatcrmlite.backend.services.sms.SmsProviderResolver;
import com.chatcrmlite.backend.services.sms.SmsSenderProvider;
import com.chatcrmlite.backend.services.sms.SmsService;
import com.chatcrmlite.backend.utils.TenantResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/sms")
@RequiredArgsConstructor
public class SmsConfigController {

    private final SmsProviderRepository providerRepository;
    private final SmsProviderResolver providerResolver;
    private final SmsService smsService;
    private final TenantResolver tenantResolver;
    private final ObjectMapper objectMapper;

    @Data
    @Builder
    public static class MaskedProviderResponse {
        private String id;
        private String businessId;
        private String providerType;
        private String name;
        private String senderId;
        private Boolean isDefault;
        private String status;
        private SmsProviderCapabilities capabilities;
        private Map<String, String> maskedCredentials;
    }

    @Data
    public static class SaveProviderRequest {
        private String id;
        private String providerType; // TWILIO, MSG91, FAST2SMS, AWS_SNS
        private String name;
        private String senderId;
        private Boolean isDefault;
        private String accountSid;
        private String authToken;
        private String apiKey;
        private String apiSecret;
    }

    @GetMapping("/providers")
    public ResponseEntity<List<MaskedProviderResponse>> getProviders(
            @RequestHeader(name = "X-Tenant-ID", required = false) String xTenantId,
            @RequestParam(name = "businessId", required = false) String businessIdParam) {

        String businessId = tenantResolver.resolveBusinessId(xTenantId, businessIdParam);
        List<SmsProvider> providers = providerRepository.findByBusinessId(businessId);
        List<MaskedProviderResponse> responseList = new ArrayList<>();

        for (SmsProvider p : providers) {
            Map<String, String> masked = new HashMap<>();
            try {
                SmsProviderCredentials creds = objectMapper.readValue(p.getCredentialsPayload(), SmsProviderCredentials.class);
                if (creds.getAccountSid() != null && !creds.getAccountSid().isBlank()) masked.put("accountSid", maskString(creds.getAccountSid()));
                if (creds.getAuthToken() != null && !creds.getAuthToken().isBlank()) masked.put("authToken", "••••••••••••");
                if (creds.getApiKey() != null && !creds.getApiKey().isBlank()) masked.put("apiKey", maskString(creds.getApiKey()));
            } catch (Exception e) {
                log.error("Failed to parse credentials for provider {}", p.getId());
            }

            SmsProviderCapabilities caps = providerResolver.resolve(p.getProviderType())
                    .map(SmsSenderProvider::getCapabilities)
                    .orElse(null);

            responseList.add(MaskedProviderResponse.builder()
                    .id(p.getId())
                    .businessId(p.getBusinessId())
                    .providerType(p.getProviderType())
                    .name(p.getName())
                    .senderId(p.getSenderId())
                    .isDefault(p.getIsDefault())
                    .status(p.getStatus())
                    .capabilities(caps)
                    .maskedCredentials(masked)
                    .build());
        }

        return ResponseEntity.ok(responseList);
    }

    @PostMapping("/providers")
    @Transactional
    public ResponseEntity<MaskedProviderResponse> saveProvider(
            @RequestHeader(name = "X-Tenant-ID", required = false) String xTenantId,
            @RequestParam(name = "businessId", required = false) String businessIdParam,
            @RequestBody SaveProviderRequest request) {

        String businessId = tenantResolver.resolveBusinessId(xTenantId, businessIdParam);
        String providerId = request.getId() != null && !request.getId().isBlank()
                ? request.getId()
                : "prov_" + System.currentTimeMillis();

        SmsProvider provider = providerRepository.findByIdAndBusinessId(providerId, businessId)
                .orElseGet(SmsProvider::new);

        provider.setId(providerId);
        provider.setBusinessId(businessId);
        provider.setProviderType(request.getProviderType().toUpperCase());
        provider.setName(request.getName());
        provider.setSenderId(request.getSenderId());

        boolean isDef = Boolean.TRUE.equals(request.getIsDefault());
        provider.setIsDefault(isDef);
        provider.setStatus("CONNECTED");

        // Un-default other providers if this provider is set to default
        if (isDef) {
            List<SmsProvider> existingList = providerRepository.findByBusinessId(businessId);
            for (SmsProvider p : existingList) {
                if (!p.getId().equals(providerId) && Boolean.TRUE.equals(p.getIsDefault())) {
                    p.setIsDefault(false);
                    providerRepository.save(p);
                }
            }
        }

        // Build typed credentials payload
        SmsProviderCredentials creds = SmsProviderCredentials.builder()
                .accountSid(request.getAccountSid())
                .authToken(request.getAuthToken())
                .apiKey(request.getApiKey())
                .apiSecret(request.getApiSecret())
                .senderId(request.getSenderId())
                .build();

        try {
            provider.setCredentialsPayload(objectMapper.writeValueAsString(creds));
        } catch (Exception e) {
            log.error("Failed to serialize credentials payload", e);
            return ResponseEntity.badRequest().build();
        }

        SmsProvider saved = providerRepository.save(provider);
        log.info("Saved SMS Provider {} ({}) for business {}", saved.getId(), saved.getProviderType(), businessId);

        Map<String, String> masked = new HashMap<>();
        if (creds.getAccountSid() != null && !creds.getAccountSid().isBlank()) masked.put("accountSid", maskString(creds.getAccountSid()));
        if (creds.getAuthToken() != null && !creds.getAuthToken().isBlank()) masked.put("authToken", "••••••••••••");
        if (creds.getApiKey() != null && !creds.getApiKey().isBlank()) masked.put("apiKey", maskString(creds.getApiKey()));

        SmsProviderCapabilities caps = providerResolver.resolve(saved.getProviderType())
                .map(SmsSenderProvider::getCapabilities)
                .orElse(null);

        return ResponseEntity.ok(MaskedProviderResponse.builder()
                .id(saved.getId())
                .businessId(saved.getBusinessId())
                .providerType(saved.getProviderType())
                .name(saved.getName())
                .senderId(saved.getSenderId())
                .isDefault(saved.getIsDefault())
                .status(saved.getStatus())
                .capabilities(caps)
                .maskedCredentials(masked)
                .build());
    }

    @DeleteMapping("/providers/{id}")
    @Transactional
    public ResponseEntity<Void> deleteProvider(
            @PathVariable("id") String id,
            @RequestHeader(name = "X-Tenant-ID", required = false) String xTenantId,
            @RequestParam(name = "businessId", required = false) String businessIdParam) {
        String businessId = tenantResolver.resolveBusinessId(xTenantId, businessIdParam);
        providerRepository.findByIdAndBusinessId(id, businessId)
                .ifPresent(providerRepository::delete);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/send")
    public ResponseEntity<SmsSendResult> sendSms(
            @RequestHeader(name = "X-Tenant-ID", required = false) String xTenantId,
            @RequestParam(name = "businessId", required = false) String businessIdParam,
            @RequestBody SmsSendRequest request) {
        String businessId = tenantResolver.resolveBusinessId(xTenantId, businessIdParam);
        SmsSendResult result = smsService.sendSms(businessId, request);
        return ResponseEntity.ok(result);
    }

    private String maskString(String str) {
        if (str == null || str.length() <= 6) return "******";
        return str.substring(0, 3) + "******" + str.substring(str.length() - 3);
    }
}
