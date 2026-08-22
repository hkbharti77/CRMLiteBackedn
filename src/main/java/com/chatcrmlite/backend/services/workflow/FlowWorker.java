package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.services.DeadLetterHandler;
import com.chatcrmlite.backend.services.flow.FlowStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Stage Worker for Flow Execution (State Machine).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowWorker implements StreamListener<String, ObjectRecord<String, String>> {

    private final WorkflowOrchestrator orchestrator;
    private final FlowStateMachine flowStateMachine;
    private final StringRedisTemplate redisTemplate;
    private final DeadLetterHandler dlqHandler;
    private final com.chatcrmlite.backend.services.whatsapp.WhatsAppFlowHandler whatsappFlowHandler;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

        log.info("🔀 [Flow-Worker] Executing flow for message {}", context.getMessageId());

        try {
            // EXECUTE FLOW LOGIC
            whatsappFlowHandler.executeFlowLogic(context);
            
            orchestrator.completeStage(context, ProcessingContext.WorkflowStage.DELIVERY);
            redisTemplate.opsForStream().acknowledge(groupName, record);
        } catch (Exception e) {
            log.error("Flow Execution failed for {}", context.getMessageId(), e);
            orchestrator.completeStage(context, ProcessingContext.WorkflowStage.FAILED);
            dlqHandler.moveToDlq(record, e);
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
