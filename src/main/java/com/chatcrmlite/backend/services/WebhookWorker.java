package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Autowired;

@Service
@RequiredArgsConstructor
public class WebhookWorker implements StreamListener<String, ObjectRecord<String, String>> {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebhookWorker.class);

    private final com.chatcrmlite.backend.services.workflow.WorkflowOrchestrator workflowOrchestrator;
    private final com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsappConfigRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final DeadLetterHandler dlqHandler;
    private final com.chatcrmlite.backend.services.tenant.TenantResourceManager resourceManager;
    private final com.chatcrmlite.backend.repositories.WhatsAppTemplateRepository whatsappTemplateRepository;
    private final com.chatcrmlite.backend.repositories.TenantRepository tenantRepository;
    @Autowired private com.chatcrmlite.backend.services.whatsapp.campaign.CampaignAnalyticsService campaignAnalyticsService;
    @Autowired private RedisStateService redisStateService;

    @Value("${whatsapp.async.stream.ingress}")
    private String streamName;

    @Value("${whatsapp.async.group}")
    private String groupName;

    @Value("${whatsapp.async.max-retries}")
    private int maxRetries;


    @Override
    public void onMessage(ObjectRecord<String, String> record) {
        String payload = record.getValue();
        String messageId = record.getId().toString();

        // Skip initialization dummy messages
        if ("true".equals(payload) || (payload != null && payload.contains("_init"))) {
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }

        log.info("⚙️ [Worker] Processing message {} from stream {}", messageId, streamName);

        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(payload);
            com.fasterxml.jackson.databind.JsonNode entry = root.path("entry").get(0);
            com.fasterxml.jackson.databind.JsonNode change = entry.path("changes").get(0);
            String field = change.path("field").asText("");
            com.fasterxml.jackson.databind.JsonNode value = change.path("value");
            
            boolean handedToOrchestrator = false;

            if ("messages".equals(field)) {
                com.fasterxml.jackson.databind.JsonNode messages = value.path("messages");
                com.fasterxml.jackson.databind.JsonNode statuses = value.path("statuses");

                if (messages.isArray() && messages.size() > 0) {
                    com.fasterxml.jackson.databind.JsonNode firstMsg = messages.get(0);
                    String waId = firstMsg.path("from").asText();
                    String waMessageId = firstMsg.path("id").asText();
                    String phoneNumberId = value.path("metadata").path("phone_number_id").asText();

                    java.util.UUID tenantId = whatsappConfigRepository
                            .findTenantIdByPhoneNumberId(phoneNumberId.trim())
                            .orElseGet(() -> {
                                log.warn("⚠️ [Worker] No matching WhatsAppConfig found for phone_number_id: {}. Attempting fallback to default tenant.", phoneNumberId);
                                return tenantRepository.findAll().stream().findFirst().map(com.chatcrmlite.backend.models.Tenant::getId).orElse(null);
                            });

                    if (tenantId != null) {
                        if (!resourceManager.canConsume(tenantId, 
                                com.chatcrmlite.backend.services.tenant.TenantResourceManager.ResourceType.MESSAGES_PER_SECOND, 1)) {
                            log.warn("🚨 [Rate-Limit] Tenant {} exceeded message rate limit. Dropping message {}", tenantId, waMessageId);
                            redisTemplate.opsForStream().acknowledge(groupName, record);
                            return;
                        }
                        workflowOrchestrator.startWorkflow(waMessageId, waId, tenantId, payload);
                        handedToOrchestrator = true;
                    } else {
                        log.warn("⚠️ [Worker] No tenant found in system for phone_number_id: {}", phoneNumberId);
                    }
                } else if (statuses.isArray() && statuses.size() > 0) {
                    for (com.fasterxml.jackson.databind.JsonNode statusNode : statuses) {
                        String waMsgId = statusNode.path("id").asText("");
                        String statusStr = statusNode.path("status").asText("");
                        String errorReason = statusNode.has("errors") ? statusNode.path("errors").toString() : null;
                        
                        log.info("[Worker] WhatsApp delivery status id={} recipient={} status={} timestamp={} conversationId={}",
                                waMsgId,
                                statusNode.path("recipient_id").asText(""),
                                statusStr,
                                statusNode.path("timestamp").asText(""),
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
                log.warn("🚨 [BSP] Account update for WABA {}: {}", wabaId, event);
                
                whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                    config.setAccountStatus(event);
                    whatsappConfigRepository.save(config);
                });
            } else if ("quality_update".equals(field)) {
                String event = value.path("event").asText("");
                String newQuality = value.path("quality_rating").asText("");
                String wabaId = entry.path("id").asText("");
                log.warn("🚨 [BSP] Quality update for WABA {}: {} -> {}", wabaId, event, newQuality);
                
                whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                    config.setQualityRating(newQuality);
                    whatsappConfigRepository.save(config);
                });
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
                String event = value.path("event").asText("");
                String wabaId = entry.path("id").asText("");
                log.info("ℹ️ [BSP] Phone number name update for WABA {}: {} ({})", wabaId, newName, event);
                if ("APPROVED".equals(event)) {
                    whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                        config.setVerifiedName(newName);
                        whatsappConfigRepository.save(config);
                    });
                }
            } else {
                log.info("ℹ️ [BSP] Unhandled webhook field: {}", field);
            }


            // ACKNOWLEDGE successful ingress
            redisTemplate.opsForStream().acknowledge(groupName, record);
            redisStateService.delete("worker:retry:" + messageId);
            if (handedToOrchestrator) {
                log.info("✅ [Ingress] Handed message {} to orchestrator", messageId);
            } else {
                log.info("[Ingress] Processed webhook callback {}", messageId);
            }

        } catch (Exception e) {
            handleFailure(record, e);
        }
    }

    private void handleFailure(ObjectRecord<String, String> record, Exception e) {
        String messageId = record.getId().toString();
        String retryKey = "worker:retry:" + messageId;
        Long currentRetries = redisStateService.increment(retryKey, java.time.Duration.ofHours(1));

        if (currentRetries != null && currentRetries <= maxRetries) {
            log.warn("⚠️ [Worker] Failed to process message {}. Retry {}/{} after error: {}", 
                    messageId, currentRetries, maxRetries, e.getMessage());
        } else {
            log.error("❌ [Worker] Max retries reached for message {}. Sending to DLQ.", messageId);
            dlqHandler.moveToDlq(record, e);
            redisTemplate.opsForStream().acknowledge(groupName, record);
            redisStateService.delete(retryKey);
        }
    }
}
