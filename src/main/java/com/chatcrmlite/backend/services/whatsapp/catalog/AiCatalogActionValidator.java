package com.chatcrmlite.backend.services.whatsapp.catalog;

import com.chatcrmlite.backend.dto.ai.action.AiAction;
import com.chatcrmlite.backend.dto.ai.action.AiAction.AiActionType;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.AiActionLogRepository;
import com.chatcrmlite.backend.repositories.TenantAiCatalogRepository;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppSendEligibilityService;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppSendEligibilityService.EligibilityResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiCatalogActionValidator {

    private final TenantAiCatalogRepository catalogRepository;
    private final AiActionLogRepository actionLogRepository;
    private final WhatsAppSendEligibilityService eligibilityService;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public record ValidationResult(
            boolean approved,
            String stage,
            String reason,
            String idempotencyKey,
            TenantAiCatalog catalog
    ) {
        public static ValidationResult approve(TenantAiCatalog catalog, String idempotencyKey) {
            return new ValidationResult(true, "PASSED", "Validation passed.", idempotencyKey, catalog);
        }

        public static ValidationResult reject(String stage, String reason) {
            return new ValidationResult(false, stage, reason, null, null);
        }
    }

    /**
     * Validates proposed AiAction against tenant isolation, status, atomic execution lock,
     * cooldown window, and WhatsApp eligibility rules.
     */
    public ValidationResult validate(UUID tenantId, Contact contact, AiAction action, WhatsAppConfig config, String messageId) {
        if (action == null || action.type() != AiActionType.SEND_CATALOG || action.catalogId() == null) {
            return ValidationResult.reject("NON_DISPATCH_ACTION", "Action is not SEND_CATALOG or catalog ID is null.");
        }

        // 1. Feature Flag Check
        if (config == null || !Boolean.TRUE.equals(config.getEnableAiCatalogs())) {
            recordAudit(tenantId, contact, action, messageId, "FEATURE_DISABLED", false, false, "AI Catalogs feature is disabled for tenant.", null);
            return ValidationResult.reject("FEATURE_DISABLED", "AI Catalogs feature is disabled for this tenant.");
        }

        // 2. Strict Tenant Isolation
        Optional<TenantAiCatalog> catalogOpt = catalogRepository.findByIdAndTenantId(action.catalogId(), tenantId);
        if (catalogOpt.isEmpty()) {
            recordAudit(tenantId, contact, action, messageId, "TENANT_MISMATCH", false, false, "Catalog does not exist or belong to tenant.", null);
            return ValidationResult.reject("TENANT_MISMATCH", "Catalog does not belong to this tenant.");
        }
        TenantAiCatalog catalog = catalogOpt.get();

        // 3. Document Lifecycle Status Check
        if (catalog.getStatus() != CatalogStatus.ACTIVE) {
            recordAudit(tenantId, contact, action, messageId, "INACTIVE", false, false, "Catalog status is not ACTIVE (" + catalog.getStatus() + ").", catalog);
            return ValidationResult.reject("INACTIVE", "Catalog is not active.");
        }

        // 4. Atomic Execution Guard (Idempotency Key)
        String idempotencyKey = String.format("catalog_dispatch:%s:%s:%s", tenantId, contact.getId(), catalog.getId());
        if (redisTemplate != null) {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "PROCESSING", Duration.ofSeconds(60));
            if (Boolean.FALSE.equals(acquired)) {
                recordAudit(tenantId, contact, action, messageId, "IDEMPOTENCY_DUPLICATE", false, false, "Concurrent duplicate dispatch blocked by atomic lock.", catalog);
                return ValidationResult.reject("IDEMPOTENCY_DUPLICATE", "Duplicate dispatch request received within deduplication window.");
            }
        }

        // 5. Cooldown Window Check
        int cooldownSec = config.getCatalogSendCooldownSeconds() != null ? config.getCatalogSendCooldownSeconds() : 300;
        LocalDateTime cooldownSince = LocalDateTime.now().minusSeconds(cooldownSec);
        List<AiActionLog> recentDispatches = actionLogRepository.findRecentDispatches(tenantId, contact.getId(), catalog.getId(), cooldownSince);
        if (!recentDispatches.isEmpty()) {
            releaseLock(idempotencyKey);
            recordAudit(tenantId, contact, action, messageId, "COOLDOWN", false, false, "Catalog was sent to this contact within cooldown window (" + cooldownSec + "s).", catalog);
            return ValidationResult.reject("COOLDOWN", "Catalog was already sent to this customer recently.");
        }

        // 6. WhatsApp Policy & Eligibility Check
        EligibilityResult eligibility = eligibilityService.canSendDocument(contact, config);
        if (!eligibility.allowed()) {
            releaseLock(idempotencyKey);
            recordAudit(tenantId, contact, action, messageId, "NOT_ELIGIBLE", false, false, eligibility.reason(), catalog);
            return ValidationResult.reject("NOT_ELIGIBLE", eligibility.reason());
        }

        return ValidationResult.approve(catalog, idempotencyKey);
    }

    public void releaseLock(String idempotencyKey) {
        if (redisTemplate != null && idempotencyKey != null) {
            try {
                redisTemplate.delete(idempotencyKey);
            } catch (Exception e) {
                log.warn("Failed to release Redis lock {}: {}", idempotencyKey, e.getMessage());
            }
        }
    }

    public void recordAudit(UUID tenantId, Contact contact, AiAction action, String messageId,
                            String stage, boolean validated, boolean executed, String reasonOrFailure,
                            TenantAiCatalog catalog) {
        try {
            AiActionLog logEntry = AiActionLog.builder()
                    .tenant(contact.getTenant())
                    .contactId(contact.getId())
                    .messageId(messageId)
                    .actionType(action.type().name())
                    .catalog(catalog)
                    .decisionSource(action.decisionSource() != null ? action.decisionSource().name() : "NATIVE_TOOL")
                    .rawAction(action.toString())
                    .reason(action.reason())
                    .caption(action.caption())
                    .validationStage(stage)
                    .validated(validated)
                    .executed(executed)
                    .failureReason(executed ? null : reasonOrFailure)
                    .deliveryStatus(executed ? "SENT" : "FAILED")
                    .build();
            actionLogRepository.save(logEntry);
        } catch (Exception e) {
            log.error("Failed to persist AiActionLog: {}", e.getMessage());
        }
    }

    public void markExecuted(UUID tenantId, Contact contact, AiAction action, TenantAiCatalog catalog,
                             String messageId, String providerMessageId, String idempotencyKey) {
        try {
            AiActionLog logEntry = AiActionLog.builder()
                    .tenant(contact.getTenant())
                    .contactId(contact.getId())
                    .messageId(messageId)
                    .actionType(action.type().name())
                    .catalog(catalog)
                    .decisionSource(action.decisionSource() != null ? action.decisionSource().name() : "NATIVE_TOOL")
                    .rawAction(action.toString())
                    .reason(action.reason())
                    .caption(action.caption())
                    .validationStage("PASSED")
                    .idempotencyKey(idempotencyKey)
                    .providerMessageId(providerMessageId)
                    .validated(true)
                    .executed(true)
                    .deliveryStatus("SENT")
                    .build();
            actionLogRepository.save(logEntry);
        } catch (Exception e) {
            log.error("Failed to mark AiActionLog executed: {}", e.getMessage());
        }
    }
}
