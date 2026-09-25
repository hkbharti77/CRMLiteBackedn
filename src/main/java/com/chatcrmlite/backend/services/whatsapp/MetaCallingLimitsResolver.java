package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppPhoneNumberConfig;
import com.chatcrmlite.backend.repositories.WhatsAppPhoneNumberConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetaCallingLimitsResolver {

    private final WhatsAppPhoneNumberConfigRepository phoneConfigRepository;

    public MetaCallingLimits resolve(UUID tenantId, String phoneNumberId) {
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            return MetaCallingLimits.defaultGraphLimits();
        }

        WhatsAppPhoneNumberConfig phoneConfig = phoneConfigRepository
                .findByTenantIdAndPhoneNumberId(tenantId, phoneNumberId.trim())
                .orElse(null);

        if (phoneConfig == null) {
            return MetaCallingLimits.defaultGraphLimits();
        }

        String signalingMode = phoneConfig.getSignalingMode();
        if ("SIP".equalsIgnoreCase(signalingMode)) {
            log.debug("📞 [MetaCallingLimitsResolver] Resolved SIP calling limits for phoneNumberId={}", phoneNumberId);
            return MetaCallingLimits.defaultSipLimits();
        }

        log.debug("📞 [MetaCallingLimitsResolver] Resolved Graph calling limits for phoneNumberId={}", phoneNumberId);
        return MetaCallingLimits.defaultGraphLimits();
    }
}
