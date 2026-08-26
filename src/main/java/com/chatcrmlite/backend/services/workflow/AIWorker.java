package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.services.DeadLetterHandler;
import com.chatcrmlite.backend.services.RedisStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

/**
 * Stage Worker for AI Processing (RAG, Guardrails).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIWorker implements StreamListener<String, ObjectRecord<String, String>> {

    private final WorkflowOrchestrator orchestrator;
    private final com.chatcrmlite.backend.services.whatsapp.WhatsAppAiService whatsappAiService;
    private final StringRedisTemplate redisTemplate;
    private final DeadLetterHandler dlqHandler;
    private final RedisStateService redisStateService;
    private final com.chatcrmlite.backend.services.tenant.TenantResourceManager resourceManager;
    private final com.chatcrmlite.backend.analytics.AnalyticsEmitter analyticsEmitter;
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
        long startTime = System.currentTimeMillis();

        log.info("🤖 [AI-Worker] Processing context for message {}", context.getMessageId());

        try {
            // 1. Quota Check
            if (!resourceManager.canConsume(context.getTenantId(), 
                    com.chatcrmlite.backend.services.tenant.TenantResourceManager.ResourceType.AI_TOKENS, 1)) {
                log.warn("⚠️ [AI-Worker] Quota exceeded for tenant {}", context.getTenantId());
                orchestrator.completeStage(context, ProcessingContext.WorkflowStage.FAILED);
                redisTemplate.opsForStream().acknowledge(groupName, record);
                return;
            }

            // 2. EXECUTE AI LOGIC
            whatsappAiService.evaluateAiIntake(context);
            
            // 3. Report Usage (Estimated 1 request/unit for now)
            resourceManager.reportUsage(context.getTenantId(), 
                    com.chatcrmlite.backend.services.tenant.TenantResourceManager.ResourceType.AI_TOKENS, 1);

            // ── 📊 EMIT ANALYTICS ───────────────────────────────────────────
            Map<String, Object> aiMetrics = new HashMap<>();
            aiMetrics.put("model", "gemini-1.5-pro");
            aiMetrics.put("latency", System.currentTimeMillis() - startTime);
            aiMetrics.put("status", "SUCCESS");
            analyticsEmitter.emit("AI_USAGE", context.getTenantId(), aiMetrics);
            // ────────────────────────────────────────────────────────────────

            // Transition to NEXT STAGE: FLOW_EXECUTION
            orchestrator.completeStage(context, ProcessingContext.WorkflowStage.FLOW_EXECUTION);

            redisTemplate.opsForStream().acknowledge(groupName, record);
        } catch (Exception e) {
            log.error("AI Processing failed for {}", context.getMessageId(), e);
            orchestrator.completeStage(context, ProcessingContext.WorkflowStage.FAILED);
            dlqHandler.moveToDlq(record, e);
            redisTemplate.opsForStream().acknowledge(groupName, record);
        }
    }

    private ProcessingContext deserialize(String data) {
        if (data == null || data.isBlank()) return null;
        try {
            if (data.startsWith("{\"payload\":") || data.startsWith("{\"payload\" :")) {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(data);
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
