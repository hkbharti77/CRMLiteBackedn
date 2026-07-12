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
            // 1. Parse payload to extract routing metadata
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(payload);
            com.fasterxml.jackson.databind.JsonNode value = root.path("entry").get(0).path("changes").get(0).path("value");
            com.fasterxml.jackson.databind.JsonNode messages = value.path("messages");
            com.fasterxml.jackson.databind.JsonNode statuses = value.path("statuses");
            boolean handedToOrchestrator = false;

            if (messages.isArray() && messages.size() > 0) {
                com.fasterxml.jackson.databind.JsonNode firstMsg = messages.get(0);
                String waId = firstMsg.path("from").asText();
                String waMessageId = firstMsg.path("id").asText();
                String phoneNumberId = value.path("metadata").path("phone_number_id").asText();

                // 2. Resolve tenant ID from phone number ID.
                // Using a direct scalar query avoids LazyInitializationException
                // that would occur if we fetched the whole entity outside a transaction.
                java.util.UUID tenantId = whatsappConfigRepository
                        .findTenantIdByPhoneNumberId(phoneNumberId.trim())
                        .orElse(null);

                if (tenantId != null) {
                    // 2b. Rate Limit Check
                    if (!resourceManager.canConsume(tenantId, 
                            com.chatcrmlite.backend.services.tenant.TenantResourceManager.ResourceType.MESSAGES_PER_SECOND, 1)) {
                        log.warn("🚨 [Rate-Limit] Tenant {} exceeded message rate limit. Dropping message {}", tenantId, waMessageId);
                        redisTemplate.opsForStream().acknowledge(groupName, record);
                        return;
                    }

                    // 3. START ORCHESTRATION
                    workflowOrchestrator.startWorkflow(waMessageId, waId, tenantId, payload);
                    handedToOrchestrator = true;
                } else {
                    log.warn("⚠️ [Worker] No OWNER user found for phone_number_id: {}. Check WhatsApp config in DB.", phoneNumberId);
                }
            } else if (statuses.isArray() && statuses.size() > 0) {
                for (com.fasterxml.jackson.databind.JsonNode statusNode : statuses) {
                    log.info("[Worker] WhatsApp delivery status id={} recipient={} status={} timestamp={} conversationId={}",
                            statusNode.path("id").asText(""),
                            statusNode.path("recipient_id").asText(""),
                            statusNode.path("status").asText(""),
                            statusNode.path("timestamp").asText(""),
                            statusNode.path("conversation").path("id").asText(""));
                }
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
