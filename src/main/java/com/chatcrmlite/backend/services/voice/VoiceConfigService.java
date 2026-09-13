package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.dto.voice.VoiceAssistantConfigDTO;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.voice.VoiceAssistantConfig;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceAssistantConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceConfigService {

    private final VoiceAssistantConfigRepository voiceConfigRepository;
    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public VoiceAssistantConfigDTO getVoiceConfigForTenant(User authenticatedUser) {
        Tenant tenant = resolveTenant(authenticatedUser);
        VoiceAssistantConfig config = voiceConfigRepository.findByTenantId(tenant.getId())
                .orElseGet(() -> createDefaultConfigForTenant(tenant, authenticatedUser));

        return toDTO(config);
    }

    @Transactional
    public VoiceAssistantConfigDTO updateVoiceConfigForTenant(User authenticatedUser, VoiceAssistantConfigDTO dto) {
        Tenant tenant = resolveTenant(authenticatedUser);
        VoiceAssistantConfig config = voiceConfigRepository.findByTenantId(tenant.getId())
                .orElseGet(() -> createDefaultConfigForTenant(tenant, authenticatedUser));

        if (dto.getVersion() != null && !dto.getVersion().equals(config.getVersion())) {
            log.warn("[VoiceConfig] Optimistic lock mismatch for tenant={}: DTO version={}, DB version={}",
                    tenant.getId(), dto.getVersion(), config.getVersion());
            throw new ObjectOptimisticLockingFailureException(VoiceAssistantConfig.class, config.getId());
        }

        if (dto.getAssistantName() != null) {
            config.setAssistantName(sanitize(dto.getAssistantName()));
        }
        if (dto.getGreetingText() != null) {
            config.setGreetingText(sanitize(dto.getGreetingText()));
        }
        if (dto.getPersonaPrompt() != null) {
            config.setPersonaPrompt(sanitize(dto.getPersonaPrompt()));
        }
        if (dto.getTtsVoiceId() != null && !dto.getTtsVoiceId().isBlank()) {
            String voiceId = sanitize(dto.getTtsVoiceId());
            if (voiceId.matches("^[a-zA-Z0-9-]+$")) {
                config.setTtsVoiceId(voiceId);
            } else {
                log.warn("[VoiceConfig] Rejected unknown ttsVoiceId='{}' for tenant={}", voiceId, tenant.getId());
            }
        }
        if (dto.getEnabled() != null) {
            config.setEnabled(dto.getEnabled());
        }
        config.setUpdatedBy(authenticatedUser);

        VoiceAssistantConfig saved = voiceConfigRepository.save(config);
        log.info("Updated VoiceAssistantConfig for tenant={} by user={}", tenant.getId(), authenticatedUser.getEmail());
        return toDTO(saved);
    }

    @Transactional
    public VoiceAssistantConfigDTO resetVoiceConfigToDefaults(User authenticatedUser) {
        Tenant tenant = resolveTenant(authenticatedUser);
        VoiceAssistantConfig config = voiceConfigRepository.findByTenantId(tenant.getId())
                .orElseGet(() -> createDefaultConfigForTenant(tenant, authenticatedUser));

        config.setAssistantName("Assistant");
        config.setGreetingText("Hello! How can I help you today?");
        config.setPersonaPrompt("You are a helpful, professional AI voice assistant.");
        config.setTtsVoiceId("simran"); // default female voice
        config.setEnabled(true);
        config.setUpdatedBy(authenticatedUser);

        VoiceAssistantConfig saved = voiceConfigRepository.save(config);
        log.info("Reset VoiceAssistantConfig to platform defaults for tenant={}", tenant.getId());
        return toDTO(saved);
    }

    private Tenant resolveTenant(User authenticatedUser) {
        if (authenticatedUser == null || authenticatedUser.getTenant() == null) {
            throw new IllegalStateException("Authenticated user is not linked to a valid tenant");
        }
        return authenticatedUser.getTenant();
    }

    private VoiceAssistantConfig createDefaultConfigForTenant(Tenant tenant, User createdBy) {
        VoiceAssistantConfig config = VoiceAssistantConfig.builder()
                .tenant(tenant)
                .assistantName("Assistant")
                .greetingText("Hello! How can I help you today?")
                .personaPrompt("You are a helpful, professional AI voice assistant.")
                .ttsVoiceId("simran")
                .enabled(true)
                .version(0L)
                .updatedBy(createdBy)
                .build();
        return voiceConfigRepository.save(config);
    }

    private String sanitize(String input) {
        if (input == null) return "";
        // Strip HTML/Script tags for XSS protection
        return input.replaceAll("<[^>]*>", "").trim();
    }

    private VoiceAssistantConfigDTO toDTO(VoiceAssistantConfig config) {
        return VoiceAssistantConfigDTO.builder()
                .id(config.getId())
                .assistantName(config.getAssistantName())
                .greetingText(config.getGreetingText())
                .personaPrompt(config.getPersonaPrompt())
                .ttsVoiceId(config.getTtsVoiceId() != null ? config.getTtsVoiceId() : "simran")
                .enabled(config.getEnabled())
                .version(config.getVersion())
                .updatedAt(config.getUpdatedAt())
                .updatedByEmail(config.getUpdatedBy() != null ? config.getUpdatedBy().getEmail() : null)
                .build();
    }
}
