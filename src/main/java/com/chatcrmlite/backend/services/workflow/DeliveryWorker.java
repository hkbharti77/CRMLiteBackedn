package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage Worker for Message Delivery (Meta API Calls).
 */
@Service
@RequiredArgsConstructor
public class DeliveryWorker implements StreamListener<String, ObjectRecord<String, String>> {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final WorkflowOrchestrator orchestrator;
    private final WhatsAppClient whatsappClient;
    private final WhatsAppConfigRepository configRepository;
    private final StringRedisTemplate redisTemplate;
    private final com.chatcrmlite.backend.services.whatsapp.WhatsAppDeliveryHandler whatsappDeliveryHandler;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chatcrmlite.backend.services.DeadLetterHandler dlqHandler;

    @Value("${whatsapp.async.group}")
    private String groupName;

    @Override
    public void onMessage(ObjectRecord<String, String> record) {
        String payload = record.getValue();
        String streamMessageId = record.getId().toString();

        // Skip initialization dummy messages
        if (payload == null || payload.isBlank() || "true".equals(payload) || payload.contains("_init")) {
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }

        ProcessingContext context = deserialize(payload);
        if (context == null) {
            log.warn("⚠️ [WhatsApp-Outbound] Failed to deserialize ProcessingContext for streamMessageId={}. Clearing.", streamMessageId);
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }

        log.info("[WhatsApp-Outbound] Delivering response correlationId={} messageId={} recipient={}",
                context.getMessageId(), context.getMessageId(), maskPhone(context.getWaId()));

        try {
            // EXECUTE DELIVERY
            whatsappDeliveryHandler.deliverResponse(context);

            orchestrator.completeStage(context, ProcessingContext.WorkflowStage.COMPLETED);
            redisTemplate.opsForStream().acknowledge(groupName, record);
            log.info("[WhatsApp-Queue] ACK Delivery stage streamMessageId={} messageId={}", streamMessageId, context.getMessageId());
        } catch (Exception e) {
            log.error("[WhatsApp-Outbound] FAILED correlationId={} messageId={} error={}",
                    context.getMessageId(), context.getMessageId(), e.getMessage(), e);
            orchestrator.completeStage(context, ProcessingContext.WorkflowStage.FAILED);
            if (dlqHandler != null) {
                try {
                    dlqHandler.moveToDlq(record, e);
                } catch (Exception dlqEx) {
                    log.warn("⚠️ [Delivery-Worker] Failed to write to DLQ: {}", dlqEx.getMessage());
                }
            }
            redisTemplate.opsForStream().acknowledge(groupName, record);
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 2);
    }

    private ProcessingContext deserialize(String data) {
        if (data == null || data.isBlank()) return null;
        try {
            if (data.startsWith("{\"payload\":") || data.startsWith("{\"payload\" :")) {
                JsonNode node = objectMapper.readTree(data);
                if (node.has("payload")) {
                    String inner = node.get("payload").asText();
                    return objectMapper.readValue(inner, ProcessingContext.class);
                }
            }
            return objectMapper.readValue(data, ProcessingContext.class);
        } catch (Exception e) {
            log.error("Failed to deserialize ProcessingContext: {}", data, e);
            return null;
        }
    }
}
