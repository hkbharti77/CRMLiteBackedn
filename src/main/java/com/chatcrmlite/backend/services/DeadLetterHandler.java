package com.chatcrmlite.backend.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class DeadLetterHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DeadLetterHandler.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${whatsapp.async.stream.dlq}")
    private String dlqStream;

    public DeadLetterHandler(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Moves a failed message to the Dead Letter Queue.
     */
    public void moveToDlq(MapRecord<String, String, String> record, Throwable cause) {
        String payload = record.getValue().get("payload");
        String messageId = record.getId().getValue();
        
        log.error("💀 Moving message {} to DLQ. Reason: {}", messageId, cause.getMessage());

        try {
            redisTemplate.opsForStream().add(dlqStream, Collections.singletonMap("payload", payload));
            log.info("✅ Message {} successfully moved to DLQ: {}", messageId, dlqStream);
        } catch (Exception e) {
            log.error("❌ Failed to move message to DLQ! Payload might be lost. Error: {}", e.getMessage());
        }
    }
}
