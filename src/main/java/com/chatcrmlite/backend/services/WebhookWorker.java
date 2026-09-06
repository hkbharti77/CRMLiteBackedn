package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebhookWorker implements StreamListener<String, MapRecord<String, String, String>> {
    private static final Logger log = LoggerFactory.getLogger(WebhookWorker.class);

    private final com.chatcrmlite.backend.services.workflow.WorkflowOrchestrator workflowOrchestrator;
    private final com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsappConfigRepository;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final DeadLetterHandler dlqHandler;
    private final com.chatcrmlite.backend.services.tenant.TenantResourceManager resourceManager;
    private final com.chatcrmlite.backend.repositories.WhatsAppTemplateRepository whatsappTemplateRepository;
    private final com.chatcrmlite.backend.repositories.TenantRepository tenantRepository;
    @Autowired private com.chatcrmlite.backend.services.whatsapp.campaign.CampaignAnalyticsService campaignAnalyticsService;
    @Autowired private RedisStateService redisStateService;
    @Autowired private com.chatcrmlite.backend.clients.WhatsAppClient whatsappClient;

    @Value("${whatsapp.async.stream.ingress}")
    private String streamName;

    @Value("${whatsapp.async.group}")
    private String groupName;

    @Value("${whatsapp.async.max-retries}")
    private int maxRetries;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        String rawData = record.getValue().get("payload");
        String streamMessageId = record.getId().toString();

        // Skip initialization dummy messages
        if (rawData == null || rawData.isBlank() || "true".equals(rawData) || rawData.contains("_init")) {
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }

        log.info("[WhatsApp-Queue] Consumed message streamMessageId={} from stream={}", streamMessageId, streamName);

        String payload = unwrapPayload(rawData);
        if (payload == null || payload.isBlank()) {
            log.warn("⚠️ [WhatsApp-Queue] Empty unwrapped payload for streamMessageId={}. Acknowledging to clear.", streamMessageId);
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.has("entry")) {
                log.warn("⚠️ [WhatsApp-Queue] Non-webhook payload missing 'entry' node. streamMessageId={}", streamMessageId);
                redisTemplate.opsForStream().acknowledge(groupName, record);
                return;
            }

            JsonNode entryArray = root.path("entry");
            if (!entryArray.isArray() || entryArray.isEmpty()) {
                log.warn("⚠️ [WhatsApp-Queue] Empty 'entry' array in webhook payload. streamMessageId={}", streamMessageId);
                redisTemplate.opsForStream().acknowledge(groupName, record);
                return;
            }

            JsonNode entry = entryArray.get(0);
            JsonNode changesArray = entry != null ? entry.path("changes") : null;
            if (changesArray == null || !changesArray.isArray() || changesArray.isEmpty()) {
                log.warn("⚠️ [WhatsApp-Queue] Missing 'changes' in webhook payload. streamMessageId={}", streamMessageId);
                redisTemplate.opsForStream().acknowledge(groupName, record);
                return;
            }

            JsonNode change = changesArray.get(0);
            String field = change.path("field").asText("");
            JsonNode value = change.path("value");

            boolean handedToOrchestrator = false;

            if ("messages".equals(field)) {
                JsonNode messages = value != null ? value.path("messages") : null;
                JsonNode statuses = value != null ? value.path("statuses") : null;

                if (messages != null && messages.isArray() && messages.size() > 0) {
                    JsonNode firstMsg = messages.get(0);
                    String waId = firstMsg.path("from").asText();
                    String waMessageId = firstMsg.path("id").asText();
                    String phoneNumberId = value.path("metadata").path("phone_number_id").asText();

                    log.info("[WhatsApp-Message] Parsed incoming message waMessageId={} from={} phoneNumberId={}",
                            waMessageId, waId, phoneNumberId);

                    UUID tenantId = whatsappConfigRepository
                            .findTenantIdByPhoneNumberId(phoneNumberId.trim())
                            .orElse(null);

                    if (tenantId != null) {
                        if (!resourceManager.canConsume(tenantId,
                                com.chatcrmlite.backend.services.tenant.TenantResourceManager.ResourceType.MESSAGES_PER_SECOND, 1)) {
                            log.warn("🚨 [Rate-Limit] Tenant {} exceeded message rate limit. Dropping message {}", tenantId, waMessageId);
                            redisTemplate.opsForStream().acknowledge(groupName, record);
                            return;
                        }

                        // 🔵 Send Blue Tick Read Receipt to WhatsApp user
                        try {
                            whatsappConfigRepository.findByTenantId(tenantId).ifPresent(config -> {
                                if (config.getAccessToken() != null && !config.getAccessToken().isBlank()) {
                                    whatsappClient.markAsRead(waMessageId, config.getAccessToken(), phoneNumberId.trim());
                                    log.info("🔵 [BlueTick] Sent read receipt to user for waMessageId={}", waMessageId);
                                }
                            });
                        } catch (Exception ex) {
                            log.warn("⚠️ [BlueTick] Could not send read receipt: {}", ex.getMessage());
                        }

                        log.info("[WhatsApp-Queue] Processing started waMessageId={} tenantId={}", waMessageId, tenantId);
                        workflowOrchestrator.startWorkflow(waMessageId, waId, tenantId, payload);
                        handedToOrchestrator = true;
                    } else {
                        log.warn("⚠️ [Worker] No tenant found in system for phone_number_id: {}", phoneNumberId);
                    }
                } else if (statuses != null && statuses.isArray() && statuses.size() > 0) {
                    for (JsonNode statusNode : statuses) {
                        String waMsgId = statusNode.path("id").asText("");
                        String statusStr = statusNode.path("status").asText("");
                        String errorReason = statusNode.has("errors") ? statusNode.path("errors").toString() : null;

                        log.info("[WhatsApp-Delivery] Status update waMessageId={} recipient={} status={} conversationId={}",
                                waMsgId,
                                statusNode.path("recipient_id").asText(""),
                                statusStr,
                                statusNode.path("conversation").path("id").asText(""));

                        if (!waMsgId.isBlank() && campaignAnalyticsService != null) {
                            try {
                                campaignAnalyticsService.processWebhookStatus(waMsgId, statusStr, errorReason);
                            } catch (Exception ex) {
                                log.warn("[Worker] Error updating campaign status for message {}: {}", waMsgId, ex.getMessage());
                            }
                        }
                    }
                }
            } else if ("account_update".equals(field)) {
                String event = value.path("event").asText("");
                String wabaId = entry.path("id").asText("");
                String phoneNumberId = value.path("metadata").path("phone_number_id").asText();
                log.warn("🚨 [BSP] Account update for WABA {}: event='{}'", wabaId, event);

                if (!wabaId.isBlank()) {
                    whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                        config.setAccountStatus(event);
                        whatsappConfigRepository.save(config);
                        log.info("✅ [Worker] Updated account status to '{}' for WABA {}", event, wabaId);
                    });
                } else if (!phoneNumberId.isBlank()) {
                    whatsappConfigRepository.findByPhoneNumberId(phoneNumberId).ifPresent(config -> {
                        config.setAccountStatus(event);
                        whatsappConfigRepository.save(config);
                        log.info("✅ [Worker] Updated account status to '{}' for PhoneNumberId {}", event, phoneNumberId);
                    });
                }
            } else if ("account_alerts".equals(field)) {
                String alertType = value.path("alert_type").asText(value.path("type").asText("GENERAL_ALERT"));
                String severity = value.path("alert_severity").asText("WARNING");
                String description = value.path("alert_description").asText(value.path("description").asText(""));
                String wabaId = entry.path("id").asText("");
                log.warn("⚠️ [BSP-Alert] Meta account alert for WABA {}: severity='{}' type='{}' desc='{}'", wabaId, severity, alertType, description);

                if (!wabaId.isBlank()) {
                    whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                        if ("CRITICAL".equalsIgnoreCase(severity)) {
                            config.setAccountStatus("ALERT_" + alertType);
                            whatsappConfigRepository.save(config);
                        }
                    });
                }
            } else if ("account_review_update".equals(field)) {
                String decision = value.path("decision").asText(value.path("event").asText(""));
                String wabaId = entry.path("id").asText("");
                String reason = value.path("rejection_reason").asText("");
                log.info("⚖️ [BSP-Review] Meta account review update for WABA {}: decision='{}' reason='{}'", wabaId, decision, reason);

                if (!wabaId.isBlank()) {
                    whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                        if ("APPROVED".equalsIgnoreCase(decision)) {
                            config.setAccountStatus("ACTIVE");
                            config.setVerificationStatus("VERIFIED");
                        } else if ("REJECTED".equalsIgnoreCase(decision)) {
                            config.setAccountStatus("RESTRICTED");
                        }
                        whatsappConfigRepository.save(config);
                        log.info("✅ [Worker] Applied review decision '{}' to WABA {}", decision, wabaId);
                    });
                }
            } else if ("quality_update".equals(field) || "phone_number_quality_update".equals(field)) {
                String event = value.path("event").asText("");
                String newQuality = value.has("quality_rating") ? value.path("quality_rating").asText("") : value.path("new_quality_rating").asText("");
                String currentLimit = value.path("current_limit").asText("");
                String wabaId = entry.path("id").asText("");
                String phoneNumberId = value.path("metadata").path("phone_number_id").asText();
                log.warn("🚨 [BSP] Quality update event: {} | Quality: {} | Limit: {} | WABA: {}", event, newQuality, currentLimit, wabaId);

                if (!wabaId.isBlank()) {
                    whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                        if (!newQuality.isBlank()) config.setQualityRating(newQuality);
                        whatsappConfigRepository.save(config);
                        log.info("✅ [Worker] Updated WhatsApp quality rating to '{}' for WABA {}", newQuality, wabaId);
                    });
                } else if (!phoneNumberId.isBlank()) {
                    whatsappConfigRepository.findByPhoneNumberId(phoneNumberId).ifPresent(config -> {
                        if (!newQuality.isBlank()) config.setQualityRating(newQuality);
                        whatsappConfigRepository.save(config);
                        log.info("✅ [Worker] Updated WhatsApp quality rating to '{}' for PhoneNumberId {}", newQuality, phoneNumberId);
                    });
                }
            } else if ("message_template_status_update".equals(field)) {
                String templateName = value.path("message_template_name").asText("");
                String status = value.path("event").asText("");
                String reason = value.has("reason") ? value.path("reason").asText() :
                                value.has("rejected_reason") ? value.path("rejected_reason").asText() : null;
                String wabaId = entry.path("id").asText("");
                log.info("ℹ️ [BSP] Template {} status update for WABA {}: {} (reason: {})", templateName, wabaId, status, reason);

                whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                    if (config.getTenant() != null) {
                        whatsappTemplateRepository.findByNameAndTenantId(templateName, config.getTenant().getId())
                                .ifPresent(template -> {
                                    template.setStatus(status);
                                    if (reason != null && !reason.isBlank()) {
                                        template.setRejectedReason(reason);
                                    }
                                    whatsappTemplateRepository.save(template);
                                    log.info("✅ [Worker] Updated WhatsAppTemplate '{}' status to '{}' (Reason: {})", templateName, status, reason);
                                });
                    }
                });
            } else if ("phone_number_name_update".equals(field)) {
                String newName = value.path("requested_verified_name").asText("");
                String decision = value.has("decision") ? value.path("decision").asText("") : value.path("event").asText("");
                String wabaId = entry.path("id").asText("");
                String phoneNumberId = value.path("metadata").path("phone_number_id").asText();
                log.info("ℹ️ [BSP] Phone number name update for WABA {}: name='{}' decision='{}'", wabaId, newName, decision);

                if ("APPROVED".equalsIgnoreCase(decision)) {
                    if (!wabaId.isBlank()) {
                        whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                            config.setVerifiedName(newName);
                            whatsappConfigRepository.save(config);
                            log.info("✅ [Worker] Updated verified name to '{}' for WABA {}", newName, wabaId);
                        });
                    } else if (!phoneNumberId.isBlank()) {
                        whatsappConfigRepository.findByPhoneNumberId(phoneNumberId).ifPresent(config -> {
                            config.setVerifiedName(newName);
                            whatsappConfigRepository.save(config);
                            log.info("✅ [Worker] Updated verified name to '{}' for PhoneNumberId {}", newName, phoneNumberId);
                        });
                    }
                }
            } else if ("smb_app_state_sync".equals(field)) {
                String eventType = value.path("event_type").asText(value.path("action").asText("STATE_SYNC"));
                String waId = value.path("wa_id").asText(value.path("chat_id").asText(""));
                log.info("📱 [SMB-State-Sync] WhatsApp Business app state sync event: {} for waId: {}", eventType, waId);
            } else {
                log.info("ℹ️ [BSP] Unhandled webhook field: {}", field);
            }

            // ACKNOWLEDGE successful ingress
            redisTemplate.opsForStream().acknowledge(groupName, record);
            redisStateService.delete("worker:retry:" + streamMessageId);
            if (handedToOrchestrator) {
                log.info("[WhatsApp-Queue] ACK message handed to orchestrator. streamMessageId={}", streamMessageId);
            } else {
                log.info("[WhatsApp-Queue] ACK callback processed. streamMessageId={}", streamMessageId);
            }

        } catch (Exception e) {
            handleFailure(record, e);
        }
    }

    private String unwrapPayload(String data) {
        if (data == null || data.isBlank()) return null;
        try {
            if (data.startsWith("{\"payload\":") || data.startsWith("{\"payload\" :")) {
                JsonNode node = objectMapper.readTree(data);
                if (node.has("payload")) {
                    return node.get("payload").asText();
                }
            }
            return data;
        } catch (Exception e) {
            return data;
        }
    }

    private void handleFailure(MapRecord<String, String, String> record, Exception e) {
        String messageId = record.getId().toString();
        String retryKey = "worker:retry:" + messageId;
        Long currentRetries = redisStateService.increment(retryKey, java.time.Duration.ofHours(1));

        log.error("[WhatsApp-Queue] FAILED streamMessageId={} error={}", messageId, e.getMessage(), e);

        if (currentRetries != null && currentRetries <= maxRetries) {
            log.warn("⚠️ [WhatsApp-Queue] Retry {}/{} scheduled for streamMessageId={}",
                    currentRetries, maxRetries, messageId);
        } else {
            log.error("❌ [WhatsApp-Queue] Max retries reached for streamMessageId={}. Routing to DLQ.", messageId);
            dlqHandler.moveToDlq(record, e);
            redisTemplate.opsForStream().acknowledge(groupName, record);
            redisStateService.delete(retryKey);
        }
    }
}
