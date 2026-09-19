package com.chatcrmlite.backend.services.payments;

import com.chatcrmlite.backend.dto.payments.TenantPaymentConfigDto;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationStatus;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.models.payments.TenantPaymentConfig;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.payments.TenantPaymentConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class TenantPaymentConfigService {

    private final TenantPaymentConfigRepository configRepository;
    private final WhatsAppConfigRepository whatsAppConfigRepository;

    @Value("${app.backend-base-url:https://api.chatcrm.local}")
    private String backendBaseUrl;

    @Transactional(readOnly = true)
    public List<TenantPaymentConfigDto> listConfigs(UUID tenantId) {
        return configRepository.findAllByTenantId(tenantId).stream()
            .map(this::mapToDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<TenantPaymentConfigDto> getConfigByType(UUID tenantId, PaymentIntegrationType type) {
        return configRepository.findActiveByTenantIdAndType(tenantId, type)
            .map(this::mapToDto);
    }

    @Transactional
    public TenantPaymentConfigDto saveOrUpdateConfig(UUID tenantId, TenantPaymentConfigDto dto) {
        Optional<TenantPaymentConfig> existingOpt = configRepository.findAllByTenantId(tenantId).stream()
            .filter(c -> c.getIntegrationType() == dto.getIntegrationType())
            .findFirst();

        TenantPaymentConfig config;
        String generatedRawWebhookKey = null;

        if (existingOpt.isPresent()) {
            config = existingOpt.get();
            config.setStatus(dto.getStatus() != null ? dto.getStatus() : PaymentIntegrationStatus.ACTIVE);
            config.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : true);
            config.setCurrency(dto.getCurrency() != null ? dto.getCurrency() : "INR");

            if (dto.getKeyId() != null && !dto.getKeyId().isBlank()) {
                config.setKeyId(dto.getKeyId());
            }
            if (dto.getKeySecret() != null && !dto.getKeySecret().isBlank()) {
                config.setKeySecret(dto.getKeySecret());
            }
            if (dto.getWebhookSecret() != null && !dto.getWebhookSecret().isBlank()) {
                config.setWebhookSecret(dto.getWebhookSecret());
            }
            if (dto.getMetaPaymentConfigurationName() != null) {
                config.setMetaPaymentConfigurationName(dto.getMetaPaymentConfigurationName());
            }
        } else {
            // Generate 256-bit secure webhook key
            byte[] randomBytes = new byte[32];
            new SecureRandom().nextBytes(randomBytes);
            generatedRawWebhookKey = HexFormat.of().formatHex(randomBytes);
            String hash = sha256(generatedRawWebhookKey);

            Tenant tenant = new Tenant();
            tenant.setId(tenantId);

            config = TenantPaymentConfig.builder()
                .integrationType(dto.getIntegrationType())
                .status(PaymentIntegrationStatus.ACTIVE)
                .isActive(true)
                .webhookKeyHash(hash)
                .currency(dto.getCurrency() != null ? dto.getCurrency() : "INR")
                .keyId(dto.getKeyId())
                .keySecret(dto.getKeySecret())
                .webhookSecret(dto.getWebhookSecret())
                .metaPaymentConfigurationName(dto.getMetaPaymentConfigurationName())
                .build();
            config.setTenant(tenant);

            if (dto.getIntegrationType() == PaymentIntegrationType.META_WHATSAPP) {
                Optional<WhatsAppConfig> waConfigOpt = whatsAppConfigRepository.findByTenantId(tenantId);
                waConfigOpt.ifPresent(config::setWhatsappAccount);
            }
        }

        config = configRepository.save(config);
        TenantPaymentConfigDto resultDto = mapToDto(config);
        if (generatedRawWebhookKey != null) {
            resultDto.setWebhookKey(generatedRawWebhookKey); // Revealed only once upon creation
            resultDto.setWebhookUrl(backendBaseUrl + "/api/v1/payments/webhook/" + generatedRawWebhookKey + "/" + config.getIntegrationType().name());
        }

        log.info("Saved payment configuration type={} for tenant={}", config.getIntegrationType(), tenantId);
        return resultDto;
    }

    @Transactional
    public TenantPaymentConfigDto regenerateWebhookKey(UUID tenantId, UUID configId) {
        TenantPaymentConfig config = configRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow(() -> new NoSuchElementException("Payment configuration not found: " + configId));

        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        String rawKey = HexFormat.of().formatHex(randomBytes);
        config.setWebhookKeyHash(sha256(rawKey));
        configRepository.save(config);

        TenantPaymentConfigDto dto = mapToDto(config);
        dto.setWebhookKey(rawKey);
        dto.setWebhookUrl(backendBaseUrl + "/api/v1/payments/webhook/" + rawKey + "/" + config.getIntegrationType().name());
        return dto;
    }

    private TenantPaymentConfigDto mapToDto(TenantPaymentConfig c) {
        String maskedKeyId = mask(c.getKeyId());
        String maskedSecret = (c.getKeySecret() != null && !c.getKeySecret().isBlank()) ? "••••••••••••" : null;
        String maskedWebhookSecret = (c.getWebhookSecret() != null && !c.getWebhookSecret().isBlank()) ? "••••••••••••" : null;

        return TenantPaymentConfigDto.builder()
            .id(c.getId())
            .integrationType(c.getIntegrationType())
            .status(c.getStatus())
            .isActive(c.getIsActive())
            .keyId(maskedKeyId)
            .keySecret(maskedSecret)
            .webhookSecret(maskedWebhookSecret)
            .metaPaymentConfigurationName(c.getMetaPaymentConfigurationName())
            .currency(c.getCurrency())
            .createdAt(c.getCreatedAt())
            .updatedAt(c.getUpdatedAt())
            .build();
    }

    private String mask(String val) {
        if (val == null || val.length() <= 8) return "••••";
        return val.substring(0, 4) + "••••" + val.substring(val.length() - 4);
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
