package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppPhoneNumberConfig;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppPhoneNumberConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessUsernameService {

    private final WhatsAppPhoneNumberConfigRepository phoneNumberConfigRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final TenantRepository tenantRepository;

    @Transactional
    public void handleUsernameUpdate(JsonNode entry, JsonNode change, Instant fallbackTimestamp) {
        if (change == null) return;
        JsonNode value = change.path("value");
        if (value.isMissingNode() || value.isNull()) return;

        String phoneNumberId = value.path("phone_number_id").asText(null);
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            phoneNumberId = value.path("metadata").path("phone_number_id").asText(null);
        }

        String wabaId = entry.path("id").asText(null);

        UUID tenantId = null;
        if (phoneNumberId != null && !phoneNumberId.isBlank()) {
            tenantId = whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId.trim()).orElse(null);
        }
        if (tenantId == null && wabaId != null && !wabaId.isBlank()) {
            tenantId = whatsappConfigRepository.findTenantIdByWabaId(wabaId.trim()).orElse(null);
        }

        if (tenantId == null) {
            log.warn("⚠️ [BusinessUsername] No tenant found for phoneNumberId={} wabaId={}", phoneNumberId, wabaId);
            return;
        }

        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return;

        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            log.warn("⚠️ [BusinessUsername] Missing phone_number_id in business_username_updates payload");
            return;
        }

        String username = value.path("username").asText(null);
        String rawStatus = value.path("status").asText("");
        String normalizedStatus = rawStatus.trim().toUpperCase();

        String finalPhoneId = phoneNumberId.trim();
        String finalWabaId = wabaId != null ? wabaId.trim() : null;
        WhatsAppPhoneNumberConfig phoneConfig = phoneNumberConfigRepository
                .findByTenantIdAndPhoneNumberId(tenantId, finalPhoneId)
                .orElseGet(() -> {
                    WhatsAppPhoneNumberConfig cfg = WhatsAppPhoneNumberConfig.builder()
                            .wabaId(finalWabaId)
                            .phoneNumberId(finalPhoneId)
                            .build();
                    cfg.setTenant(tenant);
                    return cfg;
                });
        if (finalWabaId != null && phoneConfig.getWabaId() == null) {
            phoneConfig.setWabaId(finalWabaId);
        }

        phoneConfig.setBusinessUsernameStatus(normalizedStatus);
        phoneConfig.setBusinessUsernameUpdatedAt(Instant.now());

        if ("DELETED".equals(normalizedStatus)) {
            phoneConfig.setBusinessUsername(null);
            log.info("🗑️ [BusinessUsername] Username deleted/cleared for phoneNumberId={}", finalPhoneId);
        } else {
            if (username != null && !username.isBlank()) {
                phoneConfig.setBusinessUsername(username.trim());
            }
            log.info("🏷️ [BusinessUsername] Username updated for phoneNumberId={}: username='{}' status='{}'",
                    finalPhoneId, phoneConfig.getBusinessUsername(), normalizedStatus);
        }

        phoneNumberConfigRepository.save(phoneConfig);
    }
}
