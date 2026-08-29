package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.services.DeadLetterHandler;
import com.chatcrmlite.backend.services.RedisStateService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage Worker for AI Processing (RAG, Guardrails).
 */
@Service
@RequiredArgsConstructor
public class AIWorker implements StreamListener<String, ObjectRecord<String, String>> {
    private static final Logger log = LoggerFactory.getLogger(AIWorker.class);

    private final WorkflowOrchestrator orchestrator;
    private final com.chatcrmlite.backend.services.whatsapp.WhatsAppAiService whatsappAiService;
    private final StringRedisTemplate redisTemplate;
    private final DeadLetterHandler dlqHandler;
    private final RedisStateService redisStateService;
    private final com.chatcrmlite.backend.services.tenant.TenantResourceManager resourceManager;
    private final com.chatcrmlite.backend.analytics.AnalyticsEmitter analyticsEmitter;
    private final ObjectMapper objectMapper;

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
            log.warn("⚠️ [WhatsApp-Bot] Failed to deserialize ProcessingContext for streamMessageId={}. Clearing.", streamMessageId);
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }
        long startTime = System.currentTimeMillis();

        log.info("[WhatsApp-Bot] AI processing started correlationId={} messageId={} tenantId={}",
                context.getMessageId(), context.getMessageId(), context.getTenantId());

        try {
            // 1. Quota Check
            if (!resourceManager.canConsume(context.getTenantId(), 
                    com.chatcrmlite.backend.services.tenant.TenantResourceManager.ResourceType.AI_TOKENS, 1)) {
                log.warn("⚠️ [WhatsApp-Bot] Quota exceeded for tenant {} messageId={}", context.getTenantId(), context.getMessageId());
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

            log.info("[WhatsApp-Bot] Response generated correlationId={} responseType={}",
                    context.getMessageId(), context.getMetadata().get("responseType"));

            // Transition to NEXT STAGE: FLOW_EXECUTION
            orchestrator.completeStage(context, ProcessingContext.WorkflowStage.FLOW_EXECUTION);

            redisTemplate.opsForStream().acknowledge(groupName, record);
            log.info("[WhatsApp-Queue] ACK AI stage streamMessageId={} messageId={}", streamMessageId, context.getMessageId());
        } catch (Exception e) {
            log.error("[WhatsApp-Bot] FAILED correlationId={} messageId={} error={}",
                    context.getMessageId(), context.getMessageId(), e.getMessage(), e);
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
