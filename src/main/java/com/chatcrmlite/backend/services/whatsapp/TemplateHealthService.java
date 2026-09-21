package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.models.CampaignPauseOutbox;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.WhatsAppTemplate;
import com.chatcrmlite.backend.repositories.CampaignPauseOutboxRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppTemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateHealthService {

    private final WhatsAppTemplateRepository templateRepository;
    private final WhatsAppConfigRepository configRepository;
    private final CampaignPauseOutboxRepository campaignPauseOutboxRepository;
    private final MetaWhatsAppClient metaWhatsAppClient;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 1. Advisory Signal: template_correct_category_detection
     * Meta detected a category mismatch.
     * STRICT RULE: DO NOT alter template.category or active billing rate.
     */
    @Transactional
    public void handleCategoryDetection(JsonNode entry, JsonNode change, Instant eventTimestamp) {
        JsonNode value = change.path("value");
        String metaTemplateId = value.path("message_template_id").asText("").trim();
        String templateName = value.path("message_template_name").asText("").trim();
        String language = value.path("message_template_language").asText("").trim();
        String correctCategory = value.has("correct_category") ? value.path("correct_category").asText("") : value.path("new_category").asText("");
        String reason = value.has("reason") ? value.path("reason").asText() : "META_ADVISORY_DETECTION";
        String wabaId = entry.path("id").asText("").trim();

        log.info("ℹ️ [TemplateHealth] Advisory category detection on template '{}' (ID: {}): Meta suggests '{}' (Reason: {})",
                templateName, metaTemplateId, correctCategory, reason);

        resolveTemplate(wabaId, metaTemplateId, templateName, language).ifPresent(template -> {
            template.setDetectedCorrectCategory(correctCategory);
            template.setCategoryCorrectionDetectedAt(eventTimestamp != null ? eventTimestamp : Instant.now());
            template.setCategoryCorrectionStatus("PENDING");
            template.setCategoryCorrectionReason(reason);
            templateRepository.save(template);

            broadcastTemplateEvent(template.getOwner().getTenant().getId(), "TEMPLATE_CATEGORY_DETECTION", template);
        });
    }

    /**
     * 2. Category Update: template_category_update
     * Scenario A: Scheduled change (category_update_timestamp present) -> triggers reconciliation epoch.
     * Scenario B: Completed change (previous_category present) -> official transition.
     */
    @Transactional
    public void handleCategoryUpdate(JsonNode entry, JsonNode change, Instant eventTimestamp) {
        JsonNode value = change.path("value");
        String metaTemplateId = value.path("message_template_id").asText("").trim();
        String templateName = value.path("message_template_name").asText("").trim();
        String language = value.path("message_template_language").asText("").trim();
        String wabaId = entry.path("id").asText("").trim();

        resolveTemplate(wabaId, metaTemplateId, templateName, language).ifPresent(template -> {
            // Check out-of-order state strictly within the Category state machine
            if (eventTimestamp != null && template.getLastCategoryEventAt() != null && eventTimestamp.isBefore(template.getLastCategoryEventAt())) {
                log.warn("⏱️ [TemplateHealth] Discarding out-of-order category update for template '{}' (Event timestamp: {}, Latest: {})",
                        templateName, eventTimestamp, template.getLastCategoryEventAt());
                return;
            }

            if (value.has("category_update_timestamp")) {
                // Scenario A: Scheduled / Impending change
                long timestampSec = value.path("category_update_timestamp").asLong();
                String correctCategory = value.has("correct_category") ? value.path("correct_category").asText() : value.path("new_category").asText();
                template.setCategoryChangeEffectiveAt(Instant.ofEpochSecond(timestampSec));
                template.setDetectedCorrectCategory(correctCategory);
                template.setCategoryCorrectionStatus("SCHEDULED");
                template.setLastCategoryEventAt(eventTimestamp != null ? eventTimestamp : Instant.now());
                templateRepository.save(template);

                log.info("📅 [TemplateHealth] Scheduled category change for template '{}' to '{}' effective at {}",
                        templateName, correctCategory, template.getCategoryChangeEffectiveAt());
            } else if (value.has("previous_category") || value.has("new_category")) {
                // Scenario B: Completed change
                String previousCategory = value.path("previous_category").asText("");
                String newCategory = value.path("new_category").asText("");
                String reason = value.path("reason").asText("META_COMPLETED_RECLASSIFICATION");

                template.setCategory(newCategory);
                template.setCategoryPreviousValue(previousCategory);
                template.setCategoryChangeEffectiveAt(eventTimestamp != null ? eventTimestamp : Instant.now());
                template.setCategoryChangeReason(reason);
                template.setCategoryCorrectionStatus("COMPLETED");
                template.setLastCategoryEventAt(eventTimestamp != null ? eventTimestamp : Instant.now());
                templateRepository.save(template);

                log.info("✅ [TemplateHealth] Applied completed category transition for template '{}': '{}' -> '{}'",
                        templateName, previousCategory, newCategory);
            }

            broadcastTemplateEvent(template.getOwner().getTenant().getId(), "TEMPLATE_CATEGORY_UPDATED", template);
        });
    }

    /**
     * 3. Quality Update: message_template_quality_update
     * Quality scoring: GREEN, YELLOW, RED.
     * STRICT RULE: Decoupled from template lifecycle status. Does NOT pause template status.
     */
    @Transactional
    public void handleQualityUpdate(JsonNode entry, JsonNode change, Instant eventTimestamp) {
        JsonNode value = change.path("value");
        String metaTemplateId = value.path("message_template_id").asText("").trim();
        String templateName = value.path("message_template_name").asText("").trim();
        String language = value.path("message_template_language").asText("").trim();
        String newQuality = value.has("new_quality_score") ? value.path("new_quality_score").asText("")
                : value.path("quality_score").asText("");
        String wabaId = entry.path("id").asText("").trim();

        log.info("📊 [TemplateHealth] Quality update on template '{}': quality={}", templateName, newQuality);

        resolveTemplate(wabaId, metaTemplateId, templateName, language).ifPresent(template -> {
            // Check out-of-order state strictly within the Quality state machine
            if (eventTimestamp != null && template.getLastQualityEventAt() != null && eventTimestamp.isBefore(template.getLastQualityEventAt())) {
                log.warn("⏱️ [TemplateHealth] Discarding out-of-order quality update for template '{}'", templateName);
                return;
            }

            template.setQualityRating(newQuality);
            template.setQualityUpdatedAt(Instant.now());
            template.setLastQualityEventAt(eventTimestamp != null ? eventTimestamp : Instant.now());
            templateRepository.save(template);

            broadcastTemplateEvent(template.getOwner().getTenant().getId(), "TEMPLATE_QUALITY_UPDATED", template);
        });
    }

    /**
     * 4. Status Update: message_template_status_update
     * Authoritative lifecycle event set: APPROVED, IN_APPEAL, PENDING, REJECTED, PENDING_DELETION, DELETED, DISABLED, FLAGGED, REINSTATED, PAUSED, etc.
     */
    @Transactional
    public void handleStatusUpdate(JsonNode entry, JsonNode change, Instant eventTimestamp, String streamMessageId) {
        JsonNode value = change.path("value");
        String metaTemplateId = value.path("message_template_id").asText("").trim();
        String templateName = value.path("message_template_name").asText("").trim();
        String language = value.path("message_template_language").asText("").trim();
        String event = value.path("event").asText("").trim().toUpperCase();
        String reason = value.has("reason") ? value.path("reason").asText()
                : value.has("rejected_reason") ? value.path("rejected_reason").asText() : null;
        String wabaId = entry.path("id").asText("").trim();

        log.info("🔔 [TemplateHealth] Status update event '{}' on template '{}' (WABA: {}, Reason: {})", event, templateName, wabaId, reason);

        resolveTemplate(wabaId, metaTemplateId, templateName, language).ifPresent(template -> {
            // Out-of-order check strictly within Status domain
            if (eventTimestamp != null && template.getLastStatusEventAt() != null && eventTimestamp.isBefore(template.getLastStatusEventAt())) {
                log.warn("⏱️ [TemplateHealth] Discarding out-of-order status update for template '{}' (Event: {}, EventAt: {}, Latest: {})",
                        templateName, event, eventTimestamp, template.getLastStatusEventAt());
                return;
            }

            template.setLastStatusEventAt(eventTimestamp != null ? eventTimestamp : Instant.now());
            if (reason != null && !reason.isBlank()) {
                template.setRejectedReason(reason);
            }

            switch (event) {
                case "APPROVED":
                case "IN_APPEAL":
                case "PENDING":
                case "REJECTED":
                case "PENDING_DELETION":
                case "DELETED":
                case "FLAGGED":
                case "LOCKED":
                case "ARCHIVED":
                case "UNARCHIVED":
                case "LIMIT_EXCEEDED":
                    template.setStatus(event);
                    templateRepository.save(template);
                    break;

                case "PAUSED":
                case "DISABLED":
                    template.setStatus(event);
                    templateRepository.save(template);
                    // Queue campaign auto-pause outbox (idempotent constraint prevents duplicate entries)
                    enqueueCampaignPause(template, streamMessageId);
                    break;

                case "REINSTATED":
                    // Canonical reconciliation: Reconcile actual post-reinstatement status from Meta Graph API
                    reconcileReinstatedTemplate(template, wabaId);
                    break;

                default:
                    log.warn("⚠️ [TemplateHealth] Unmapped Meta lifecycle event '{}' on template '{}'", event, templateName);
                    template.setStatus(event);
                    templateRepository.save(template);
                    break;
            }

            broadcastTemplateEvent(template.getOwner().getTenant().getId(), "TEMPLATE_STATUS_UPDATED", template);
        });
    }

    /**
     * 5. Component Update: message_template_components_update
     * Reconciles canonical template definition from Meta Graph API with exponential backoff.
     */
    @Transactional
    public void handleComponentsUpdate(JsonNode entry, JsonNode change, Instant eventTimestamp) {
        JsonNode value = change.path("value");
        String metaTemplateId = value.path("message_template_id").asText("").trim();
        String templateName = value.path("message_template_name").asText("").trim();
        String language = value.path("message_template_language").asText("").trim();
        String wabaId = entry.path("id").asText("").trim();

        resolveTemplate(wabaId, metaTemplateId, templateName, language).ifPresent(template -> {
            if (eventTimestamp != null && template.getLastComponentEventAt() != null && eventTimestamp.isBefore(template.getLastComponentEventAt())) {
                log.warn("⏱️ [TemplateHealth] Discarding out-of-order component update for template '{}'", templateName);
                return;
            }

            template.setLastComponentEventAt(eventTimestamp != null ? eventTimestamp : Instant.now());
            reconcileCanonicalTemplate(template, wabaId);
        });
    }

    /**
     * Reconciles canonical component tree & status from Meta Graph API.
     */
    public void reconcileCanonicalTemplate(WhatsAppTemplate template, String wabaId) {
        if (template.getMetaTemplateId() == null || template.getMetaTemplateId().isBlank()) return;

        WhatsAppConfig config = configRepository.findByTenantId(template.getOwner().getTenant().getId()).orElse(null);
        if (config == null || config.getAccessToken() == null || config.getAccessToken().isBlank()) return;

        try {
            JsonNode metaResponse = metaWhatsAppClient.fetchSingleMessageTemplate(template.getMetaTemplateId(), config.getAccessToken());
            if (metaResponse != null) {
                if (metaResponse.has("category")) {
                    template.setCategory(metaResponse.path("category").asText(template.getCategory()));
                }
                if (metaResponse.has("status")) {
                    template.setStatus(metaResponse.path("status").asText(template.getStatus()));
                }
                if (metaResponse.has("components")) {
                    JsonNode components = metaResponse.path("components");
                    parseAndApplyComponents(template, components);
                }
                template.setLastComponentSyncAt(Instant.now());
                template.setLastComponentSyncStatus("SUCCESS");
                template.setLastComponentSyncError(null);
                templateRepository.save(template);
                log.info("✅ [TemplateHealth] Reconciled canonical template '{}' from Meta Graph API", template.getName());
            }
        } catch (Exception e) {
            log.error("❌ [TemplateHealth] Error reconciling template '{}' from Meta: {}", template.getName(), e.getMessage());
            template.setLastComponentSyncAt(Instant.now());
            template.setLastComponentSyncStatus("FAILED");
            template.setLastComponentSyncError(e.getMessage());
            templateRepository.save(template);
        }
    }

    private void reconcileReinstatedTemplate(WhatsAppTemplate template, String wabaId) {
        reconcileCanonicalTemplate(template, wabaId);
        // Note: We deliberately do NOT auto-resume previously paused campaigns.
        // Campaigns remain PAUSED with reason META_TEMPLATE_PAUSED so admins can review them deliberately.
    }

    private void parseAndApplyComponents(WhatsAppTemplate template, JsonNode components) {
        if (!components.isArray()) return;
        for (JsonNode comp : components) {
            String type = comp.path("type").asText("").toUpperCase();
            if ("BODY".equals(type)) {
                template.setBodyText(comp.path("text").asText(""));
            } else if ("HEADER".equals(type)) {
                template.setHeaderType(comp.path("format").asText("NONE"));
                template.setHeaderContent(comp.path("text").asText(null));
            } else if ("FOOTER".equals(type)) {
                template.setFooterText(comp.path("text").asText(null));
            } else if ("BUTTONS".equals(type)) {
                try {
                    template.setButtonsJson(objectMapper.writeValueAsString(comp.path("buttons")));
                } catch (Exception ignored) {}
            }
        }
    }

    private void enqueueCampaignPause(WhatsAppTemplate template, String streamMessageId) {
        try {
            UUID tenantId = template.getOwner().getTenant().getId();
            boolean alreadyEnqueued = campaignPauseOutboxRepository.existsByTenantIdAndTemplateIdAndSourceEventId(
                    tenantId, template.getId(), streamMessageId);
            if (!alreadyEnqueued) {
                CampaignPauseOutbox outbox = CampaignPauseOutbox.builder()
                        .tenant(template.getOwner().getTenant())
                        .template(template)
                        .sourceEventId(streamMessageId)
                        .status("PENDING")
                        .createdAt(Instant.now())
                        .build();
                campaignPauseOutboxRepository.save(outbox);
                log.info("📥 [TemplateHealth] Enqueued campaign pause outbox for template '{}' (EventId: {})",
                        template.getName(), streamMessageId);
            }
        } catch (Exception e) {
            log.warn("⚠️ [TemplateHealth] Campaign pause outbox already queued or conflict: {}", e.getMessage());
        }
    }

    private Optional<WhatsAppTemplate> resolveTemplate(String wabaId, String metaTemplateId, String templateName, String language) {
        WhatsAppConfig config = null;
        if (wabaId != null && !wabaId.isBlank()) {
            config = configRepository.findByWabaId(wabaId).orElse(null);
        }

        UUID tenantId = config != null && config.getTenant() != null ? config.getTenant().getId() : null;

        if (metaTemplateId != null && !metaTemplateId.isBlank()) {
            if (tenantId != null) {
                Optional<WhatsAppTemplate> opt = templateRepository.findByMetaTemplateIdAndTenantId(metaTemplateId, tenantId);
                if (opt.isPresent()) return opt;
            }
            Optional<WhatsAppTemplate> opt = templateRepository.findFirstByMetaTemplateId(metaTemplateId);
            if (opt.isPresent()) return opt;
        }

        if (templateName != null && !templateName.isBlank() && tenantId != null) {
            if (language != null && !language.isBlank()) {
                Optional<WhatsAppTemplate> opt = templateRepository.findByNameAndLanguageAndTenantId(templateName, language, tenantId);
                if (opt.isPresent()) return opt;
            }
            return templateRepository.findByNameAndTenantId(templateName, tenantId);
        }

        return Optional.empty();
    }

    private void broadcastTemplateEvent(UUID tenantId, String eventType, WhatsAppTemplate template) {
        if (messagingTemplate != null && tenantId != null) {
            try {
                messagingTemplate.convertAndSend("/topic/" + tenantId + "/templates", Map.of(
                        "eventType", eventType,
                        "templateId", template.getId().toString(),
                        "name", template.getName(),
                        "category", template.getCategory(),
                        "status", template.getStatus(),
                        "qualityRating", template.getQualityRating() != null ? template.getQualityRating() : "UNKNOWN"
                ));
            } catch (Exception ex) {
                log.debug("[TemplateHealth] WebSocket broadcast failed: {}", ex.getMessage());
            }
        }
    }
}
