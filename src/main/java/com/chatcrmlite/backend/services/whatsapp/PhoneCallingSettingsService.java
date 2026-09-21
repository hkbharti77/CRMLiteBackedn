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
public class PhoneCallingSettingsService {

    private final WhatsAppPhoneNumberConfigRepository phoneNumberConfigRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final TenantRepository tenantRepository;

    @Transactional
    public void handleSettingsUpdate(JsonNode entry, JsonNode change, Instant fallbackTimestamp) {
        if (change == null) return;
        JsonNode value = change.path("value");
        if (value.isMissingNode() || value.isNull()) return;

        JsonNode settings = value.path("phone_number_settings");
        if (settings.isMissingNode() || settings.isNull()) {
            // Check fallback to value if phone_number_settings is not nested
            settings = value;
        }

        String phoneNumberId = settings.path("phone_number_id").asText(null);
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
            log.warn("⚠️ [CallingSettings] No tenant found for phoneNumberId={} wabaId={}", phoneNumberId, wabaId);
            return;
        }

        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return;

        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            log.warn("⚠️ [CallingSettings] Missing phone_number_id in account_settings_update payload");
            return;
        }

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

        JsonNode calling = settings.path("calling");
        if (!calling.isMissingNode() && !calling.isNull()) {
            if (calling.has("status")) {
                phoneConfig.setCallingStatus(calling.path("status").asText());
            }
            if (calling.has("call_icon_visibility")) {
                phoneConfig.setCallIconVisibility(calling.path("call_icon_visibility").asText());
            }
            if (calling.has("callback_permission_status")) {
                phoneConfig.setCallbackPermissionStatus(calling.path("callback_permission_status").asText());
            }
            if (calling.has("sip")) {
                phoneConfig.setSipStatus(calling.path("sip").path("status").asText(null));
            }
            if (calling.has("srtp_key_exchange_protocol")) {
                phoneConfig.setSrtpProtocol(calling.path("srtp_key_exchange_protocol").asText(null));
            }
            phoneConfig.setCallingSettingsUpdatedAt(Instant.now());
            log.info("📞 [CallingSettings] Updated calling settings for phoneNumberId={}: status='{}' icon='{}'",
                    finalPhoneId, phoneConfig.getCallingStatus(), phoneConfig.getCallIconVisibility());
        }

        // BSUID capability flags if present
        if (settings.has("bsuid_enabled")) {
            phoneConfig.setBsuidEnabled(settings.path("bsuid_enabled").asBoolean());
        }
        if (settings.has("parent_bsuid_enabled")) {
            phoneConfig.setParentBsuidEnabled(settings.path("parent_bsuid_enabled").asBoolean());
        }

        phoneNumberConfigRepository.save(phoneConfig);
    }
}
