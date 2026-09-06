package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.services.DeadLetterHandler;
import com.chatcrmlite.backend.services.flow.FlowStateMachine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage Worker for Flow Execution (State Machine).
 */
@Service
@RequiredArgsConstructor
public class FlowWorker implements StreamListener<String, MapRecord<String, String, String>> {
    private static final Logger log = LoggerFactory.getLogger(FlowWorker.class);

    private final WorkflowOrchestrator orchestrator;
    private final FlowStateMachine flowStateMachine;
    private final StringRedisTemplate redisTemplate;
    private final DeadLetterHandler dlqHandler;
    private final com.chatcrmlite.backend.services.whatsapp.WhatsAppFlowHandler whatsappFlowHandler;
    private final ObjectMapper objectMapper;

    @Value("${whatsapp.async.group}")
    private String groupName;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        String payload = record.getValue().get("payload");
        String streamMessageId = record.getId().toString();

        // Skip initialization dummy messages
        if (payload == null || payload.isBlank() || "true".equals(payload) || payload.contains("_init")) {
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }

        ProcessingContext context = deserialize(payload);
        if (context == null) {
            log.warn("⚠️ [WhatsApp-Flow] Failed to deserialize ProcessingContext for streamMessageId={}. Clearing.", streamMessageId);
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }

        log.info("[WhatsApp-Flow] Executing flow logic correlationId={} messageId={}", context.getMessageId(), context.getMessageId());

        try {
            // EXECUTE FLOW LOGIC
            whatsappFlowHandler.executeFlowLogic(context);
            
            orchestrator.completeStage(context, ProcessingContext.WorkflowStage.DELIVERY);
            redisTemplate.opsForStream().acknowledge(groupName, record);
            log.info("[WhatsApp-Queue] ACK Flow stage streamMessageId={} messageId={}", streamMessageId, context.getMessageId());
        } catch (Exception e) {
            log.error("[WhatsApp-Flow] FAILED correlationId={} messageId={} error={}", context.getMessageId(), context.getMessageId(), e.getMessage(), e);
            orchestrator.completeStage(context, ProcessingContext.WorkflowStage.FAILED);
            dlqHandler.moveToDlq(record, e);
            redisTemplate.opsForStream().acknowledge(groupName, record);
        }
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
