package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Stage Worker for Message Delivery (Meta API Calls).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryWorker implements StreamListener<String, ObjectRecord<String, String>> {

    private final WorkflowOrchestrator orchestrator;
    private final WhatsAppClient whatsappClient;
    private final WhatsAppConfigRepository configRepository;
    private final StringRedisTemplate redisTemplate;
    private final com.chatcrmlite.backend.services.whatsapp.WhatsAppDeliveryHandler whatsappDeliveryHandler;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chatcrmlite.backend.services.DeadLetterHandler dlqHandler;

    @Value("${whatsapp.async.group}")
    private String groupName;

    @Override
    public void onMessage(ObjectRecord<String, String> record) {
        String payload = record.getValue();

        // Skip initialization dummy messages
        if ("true".equals(payload) || (payload != null && payload.contains("_init"))) {
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }

        ProcessingContext context = deserialize(payload);
        if (context == null) {
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }

        log.info("🚚 [Delivery-Worker] Delivering message {}", context.getMessageId());

        try {
            // EXECUTE DELIVERY
            whatsappDeliveryHandler.deliverResponse(context);

            orchestrator.completeStage(context, ProcessingContext.WorkflowStage.COMPLETED);
            redisTemplate.opsForStream().acknowledge(groupName, record);
        } catch (Exception e) {
            log.error("❌ [Delivery-Worker] Delivery failed for messageId={}", context.getMessageId(), e);
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

    private ProcessingContext deserialize(String data) {
        try {
            return objectMapper.readValue(data, ProcessingContext.class);
        } catch (Exception e) {
            log.error("Failed to deserialize ProcessingContext: {}", data, e);
            return null;
        }
    }
}
