package com.chatcrmlite.backend.services.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class ModelHealthMonitor {

    private final StringRedisTemplate redisTemplate;
    private static final String KEY_PREFIX = "ai:health:";

    public void recordSuccess(String model, long latencyMs) {
        String key = KEY_PREFIX + model + ":latency";
        // Store as a list of recent latencies (sliding window)
        redisTemplate.opsForList().rightPush(key, String.valueOf(latencyMs));
        redisTemplate.opsForList().trim(key, -100, -1); // Keep last 100
        redisTemplate.expire(key, Duration.ofHours(1));
        
        // Reset failure count
        redisTemplate.delete(KEY_PREFIX + model + ":failures");
    }

    public void recordFailure(String model) {
        String key = KEY_PREFIX + model + ":failures";
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, Duration.ofMinutes(10));
    }

    public long getLatencyP95(String model) {
        // Mock implementation for demo
        String val = redisTemplate.opsForList().index(KEY_PREFIX + model + ":latency", -1);
        return val != null ? Long.parseLong(val) : 500; // Default 500ms
    }

    public boolean isCircuitOpen(String model) {
        String failures = redisTemplate.opsForValue().get(KEY_PREFIX + model + ":failures");
        return failures != null && Integer.parseInt(failures) > 5;
    }
}
