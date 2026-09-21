package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.SecurityNotificationOutbox;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.SecurityNotificationOutboxRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountHealthService {

    private final WhatsAppConfigRepository configRepository;
    private final SecurityNotificationOutboxRepository securityNotificationOutboxRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 1. Canonical Account Lifecycle via account_update
     * Handles restriction_info, ban_info, violation_info, and lifecycle events.
     * STRICT RULE: VERIFIED_ACCOUNT or positive events DO NOT erase active restrictions.
     */
    @Transactional
    public void handleAccountUpdate(JsonNode entry, JsonNode change, Instant eventTimestamp, String streamMessageId) {
        String wabaId = entry.path("id").asText("").trim();
        JsonNode value = change.path("value");
        String event = value.path("event").asText("").trim();

        log.info("🚨 [AccountHealth] Account update for WABA {}: event='{}'", wabaId, event);

        WhatsAppConfig config = configRepository.findByWabaId(wabaId).orElse(null);
        if (config == null) {
            log.warn("⚠️ [AccountHealth] No WhatsAppConfig mapped for WABA '{}'. Skipping account update.", wabaId);
            return;
        }

        UUID tenantId = config.getTenant() != null ? config.getTenant().getId() : null;
        boolean updated = false;

        // A. Handle Restrictions with event-level map merging (no blind overwrite)
        if (value.has("restriction_info")) {
            if (eventTimestamp == null || config.getLastRestrictionEventAt() == null || !eventTimestamp.isBefore(config.getLastRestrictionEventAt())) {
                mergeRestrictions(config, value.path("restriction_info"), eventTimestamp);
                config.setLastRestrictionEventAt(eventTimestamp != null ? eventTimestamp : Instant.now());
                updated = true;
            }
        }

        // B. Handle Ban Info
        if (value.has("ban_info")) {
            if (eventTimestamp == null || config.getLastLifecycleEventAt() == null || !eventTimestamp.isBefore(config.getLastLifecycleEventAt())) {
                try {
                    config.setBanInfoJson(objectMapper.writeValueAsString(value.path("ban_info")));
                    config.setAccountStatus("BANNED");
                    config.setAccountStatusReason("META_BAN_INFO_RECEIVED");
                    config.setAccountStatusUpdatedAt(eventTimestamp != null ? eventTimestamp : Instant.now());
                    config.setLastLifecycleEventAt(eventTimestamp != null ? eventTimestamp : Instant.now());
                    updated = true;
                } catch (Exception ex) {
                    log.error("[AccountHealth] Error writing ban_info JSON: {}", ex.getMessage());
                }
            }
        }

        // C. Handle Violations
        if (value.has("violation_info")) {
            try {
                config.setViolationJson(objectMapper.writeValueAsString(value.path("violation_info")));
                updated = true;
            } catch (Exception ex) {
                log.error("[AccountHealth] Error writing violation_info JSON: {}", ex.getMessage());
            }
        }

        // D. Handle Lifecycle Events (DISABLED_UPDATE, VERIFIED_ACCOUNT, ACCOUNT_RESTRICTION, etc.)
        if (!event.isBlank()) {
            if (eventTimestamp == null || config.getLastLifecycleEventAt() == null || !eventTimestamp.isBefore(config.getLastLifecycleEventAt())) {
                if ("DISABLED_UPDATE".equalsIgnoreCase(event) || "DISABLED".equalsIgnoreCase(event)) {
                    config.setAccountStatus("DISABLED");
                    config.setAccountStatusReason(value.has("reason") ? value.path("reason").asText() : "META_DISABLED_UPDATE");
                    config.setAccountStatusUpdatedAt(eventTimestamp != null ? eventTimestamp : Instant.now());
                    config.setLastLifecycleEventAt(eventTimestamp != null ? eventTimestamp : Instant.now());
                    updated = true;
                } else if ("VERIFIED_ACCOUNT".equalsIgnoreCase(event)) {
                    config.setVerificationStatus("VERIFIED");
                    config.setLastLifecycleEventAt(eventTimestamp != null ? eventTimestamp : Instant.now());
                    // NOTE: Deliberately DO NOT clear active restrictions here!
                    updated = true;
                } else if ("ACCOUNT_RESTRICTION".equalsIgnoreCase(event)) {
                    config.setAccountStatus("RESTRICTED");
                    config.setAccountStatusReason(value.has("reason") ? value.path("reason").asText() : "ACCOUNT_RESTRICTION_ACTIVE");
                    config.setAccountStatusUpdatedAt(eventTimestamp != null ? eventTimestamp : Instant.now());
                    config.setLastLifecycleEventAt(eventTimestamp != null ? eventTimestamp : Instant.now());
                    updated = true;
                }
            }
        }

        if (updated) {
            configRepository.save(config);
            log.info("✅ [AccountHealth] Persisted account health update for WABA {}", wabaId);
            broadcastAccountAlert(tenantId, config);
        }
    }

    /**
     * 2. Modern Business-Level Messaging Limits via business_capability_update
     */
    @Transactional
    public void handleBusinessCapabilityUpdate(JsonNode entry, JsonNode change, Instant eventTimestamp) {
        String wabaId = entry.path("id").asText("").trim();
        JsonNode value = change.path("value");

        log.info("📈 [AccountHealth] Business capability update for WABA {}: {}", wabaId, value.toString());

        WhatsAppConfig config = configRepository.findByWabaId(wabaId).orElse(null);
        if (config == null) return;

        // Out-of-order check strictly on capability domain
        if (eventTimestamp != null && config.getLastCapabilityEventAt() != null && eventTimestamp.isBefore(config.getLastCapabilityEventAt())) {
            log.warn("⏱️ [AccountHealth] Discarding out-of-order business capability update for WABA {}", wabaId);
            return;
        }

        // Modern 2026 Meta model: Business-level limits
        if (value.has("max_daily_conversations_per_business")) {
            JsonNode maxNode = value.path("max_daily_conversations_per_business");
            if (maxNode.isNumber()) {
                config.setMessagingLimitValue(maxNode.asLong());
                config.setMessagingLimitType("DAILY_CONVERSATIONS");
                config.setMessagingLimitRaw(maxNode.asText());
            } else {
                String raw = maxNode.asText("");
                config.setMessagingLimitRaw(raw);
                config.setMessagingLimitType("DAILY_CONVERSATIONS_TIER");
                if ("TIER_UNLIMITED".equalsIgnoreCase(raw)) {
                    config.setMessagingLimitValue(-1L);
                } else if (raw.startsWith("TIER_")) {
                    try {
                        String tierNum = raw.replace("TIER_", "").replace("K", "000");
                        config.setMessagingLimitValue(Long.parseLong(tierNum));
                    } catch (Exception ignored) {
                        config.setMessagingLimitValue(null);
                    }
                }
            }
        }

        if (value.has("max_phones_per_business_portfolio")) {
            config.setMaxPhonesPerBusinessPortfolio(value.path("max_phones_per_business_portfolio").asInt());
        }
        if (value.has("max_phones_per_waba")) {
            config.setMaxPhonesPerWaba(value.path("max_phones_per_waba").asInt());
        }

        try {
            config.setCapabilityJson(objectMapper.writeValueAsString(value));
        } catch (Exception ignored) {}

        config.setCapabilityUpdatedAt(Instant.now());
        config.setLastCapabilityEventAt(eventTimestamp != null ? eventTimestamp : Instant.now());
        configRepository.save(config);

        log.info("✅ [AccountHealth] Updated business capability limits for WABA {}: limit={}",
                wabaId, config.getMessagingLimitRaw());
    }

    /**
     * 3. Security Webhook: security
     * Strictly parses verified Meta wire events: PIN_CHANGED, PIN_RESET_REQUEST, PIN_REQUEST_SUCCESS.
     * Writes immutable SecurityNotificationOutbox (with database uniqueness constraint).
     */
    @Transactional
    public void handleSecurityAlert(JsonNode entry, JsonNode change, Instant eventTimestamp, String streamMessageId) {
        String wabaId = entry.path("id").asText("").trim();
        JsonNode value = change.path("value");
        String event = value.path("event").asText("").trim();
        String phoneNumberId = value.has("phone_number_id") ? value.path("phone_number_id").asText("").trim()
                : value.path("metadata").path("phone_number_id").asText("").trim();
        String metaUserId = value.has("user_id") ? value.path("user_id").asText("").trim() : null;

        log.warn("🛡️ [AccountHealth-Security] Meta Security event '{}' for WABA {} on phone {} (Meta UID: {})",
                event, wabaId, phoneNumberId, metaUserId);

        WhatsAppConfig config = configRepository.findByWabaId(wabaId).orElse(null);
        if (config == null && !phoneNumberId.isBlank()) {
            config = configRepository.findByPhoneNumberId(phoneNumberId).orElse(null);
        }
        if (config == null || config.getTenant() == null) {
            log.warn("⚠️ [AccountHealth-Security] No tenant mapped for security alert WABA {} phone {}", wabaId, phoneNumberId);
            return;
        }

        try {
            boolean exists = securityNotificationOutboxRepository
                    .existsByTenantIdAndEventTypeAndPhoneNumberIdAndSourceEventId(
                            config.getTenant().getId(), event, phoneNumberId, streamMessageId);

            if (!exists) {
                SecurityNotificationOutbox outbox = SecurityNotificationOutbox.builder()
                        .tenant(config.getTenant())
                        .eventType("WHATSAPP_SECURITY_" + event)
                        .phoneNumberId(phoneNumberId)
                        .metaUserId(metaUserId)
                        .sourceEventId(streamMessageId)
                        .description(String.format("WhatsApp security event '%s' triggered for phone %s (Meta User ID: %s)",
                                event, phoneNumberId, metaUserId != null ? metaUserId : "Platform"))
                        .status("PENDING")
                        .createdAt(eventTimestamp != null ? eventTimestamp : Instant.now())
                        .build();

                securityNotificationOutboxRepository.save(outbox);
                log.info("📥 [AccountHealth-Security] Enqueued security notification outbox entry (EventId: {})", streamMessageId);
            }
        } catch (Exception ex) {
            log.warn("⚠️ [AccountHealth-Security] Security notification outbox already queued or conflict: {}", ex.getMessage());
        }
    }

    /**
     * Merges restriction entries into restriction_json without blind replacement.
     */
    private void mergeRestrictions(WhatsAppConfig config, JsonNode restrictionNode, Instant eventTimestamp) {
        try {
            Map<String, Map<String, Object>> restrictionMap = new HashMap<>();
            if (config.getRestrictionJson() != null && !config.getRestrictionJson().isBlank()) {
                try {
                    restrictionMap = objectMapper.readValue(config.getRestrictionJson(), new TypeReference<Map<String, Map<String, Object>>>() {});
                } catch (Exception ignored) {}
            }

            if (restrictionNode.isArray()) {
                for (JsonNode item : restrictionNode) {
                    String type = item.path("restriction_type").asText("");
                    if (type.isBlank()) continue;

                    Map<String, Object> details = new HashMap<>();
                    details.put("type", type);
                    details.put("active", true);
                    if (item.has("expiration")) details.put("expiration", item.path("expiration").asText());
                    if (item.has("reason")) details.put("reason", item.path("reason").asText());
                    details.put("lastSeenAt", (eventTimestamp != null ? eventTimestamp : Instant.now()).toString());

                    restrictionMap.put(type, details);
                }
            } else if (restrictionNode.isObject()) {
                String type = restrictionNode.path("restriction_type").asText("");
                if (!type.isBlank()) {
                    Map<String, Object> details = new HashMap<>();
                    details.put("type", type);
                    details.put("active", true);
                    if (restrictionNode.has("expiration")) details.put("expiration", restrictionNode.path("expiration").asText());
                    if (restrictionNode.has("reason")) details.put("reason", restrictionNode.path("reason").asText());
                    details.put("lastSeenAt", (eventTimestamp != null ? eventTimestamp : Instant.now()).toString());

                    restrictionMap.put(type, details);
                }
            }

            config.setRestrictionJson(objectMapper.writeValueAsString(restrictionMap));
            log.info("🔒 [AccountHealth] Merged restriction map for WABA {}: {} active restrictions",
                    config.getWabaId(), restrictionMap.size());
        } catch (Exception ex) {
            log.error("[AccountHealth] Error merging restrictions: {}", ex.getMessage());
        }
    }

    private void broadcastAccountAlert(UUID tenantId, WhatsAppConfig config) {
        if (messagingTemplate != null && tenantId != null) {
            try {
                messagingTemplate.convertAndSend("/topic/" + tenantId + "/account-health", Map.of(
                        "accountStatus", config.getAccountStatus() != null ? config.getAccountStatus() : "ACTIVE",
                        "accountStatusReason", config.getAccountStatusReason() != null ? config.getAccountStatusReason() : "",
                        "canSendCampaigns", config.canSendCampaigns(),
                        "canSendUtilityTemplates", config.canSendUtilityTemplates()
                ));
            } catch (Exception ex) {
                log.debug("[AccountHealth] WebSocket broadcast failed: {}", ex.getMessage());
            }
        }
    }
}
