package com.chatcrmlite.backend.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data @Builder
class AnalyticsEvent {
    private String type; // AI_USAGE, CONVERSION, TENANT_GROWTH
    private UUID tenantId;
    private long timestamp;
    private Map<String, Object> data;
}

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsEmitter {

    private final StringRedisTemplate redisTemplate;
    private final String STREAM_KEY = "analytics:stream";

    /**
     * Emits an event to the analytics pipeline.
     */
    public void emit(String type, UUID tenantId, Map<String, Object> data) {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("type", type);
            payload.put("tenantId", tenantId.toString());
            payload.put("timestamp", String.valueOf(System.currentTimeMillis()));
            payload.put("data", new ObjectMapper().writeValueAsString(data));

            redisTemplate.opsForStream().add(STREAM_KEY, payload);
        } catch (Exception e) {
            log.error("Failed to emit analytics event", e);
        }
    }
}
