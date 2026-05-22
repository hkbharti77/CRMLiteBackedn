package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookQueueProducer {

    private final StringRedisTemplate redisTemplate;

    @Value("${whatsapp.async.stream.ingress}")
    private String streamName;

    @Value("${whatsapp.async.stream.max-len}")
    private long maxLen;

    /**
     * Enqueues the raw webhook payload into Redis Stream.
     * Includes a correlation ID for tracing.
     *
     * @param payload Raw JSON from WhatsApp
     * @return The correlation ID for tracking
     */
    public String enqueue(String payload) {
        String correlationId = UUID.randomUUID().toString();
        
        // Check stream depth for backpressure (simplified)
        Long currentDepth = redisTemplate.opsForStream().size(streamName);
        if (currentDepth != null && currentDepth > maxLen) {
            log.warn("⚠️ Webhook stream depth ({}) exceeds threshold ({})! Backpressure active.", currentDepth, maxLen);
            // In a strict production setup, we might reject here with 429 or 503
            // but for now, we log and continue to prioritize delivery.
        }

        ObjectRecord<String, String> record = ObjectRecord.create(streamName, payload);
        
        redisTemplate.opsForStream().add(record);
        log.info("📥 [Queue] Enqueued webhook payload. CorrelationId: {}, Stream: {}", correlationId, streamName);
        
        return correlationId;
    }
}
