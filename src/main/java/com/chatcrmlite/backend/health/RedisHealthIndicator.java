package com.chatcrmlite.backend.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Custom Redis health indicator that provides detailed diagnostics.
 * Exposed at /actuator/health/redis
 * Included in the readiness probe group so pods only receive traffic
 * when Redis is reachable.
 */
@Slf4j
@Component("redis")
@RequiredArgsConstructor
public class RedisHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redisTemplate;

    @Override
    public Health health() {
        try {
            long start = System.currentTimeMillis();
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            long latencyMs = System.currentTimeMillis() - start;

            if ("PONG".equalsIgnoreCase(pong)) {
                return Health.up()
                        .withDetail("latency_ms", latencyMs)
                        .withDetail("status", "CONNECTED")
                        .build();
            } else {
                return Health.down()
                        .withDetail("response", pong)
                        .build();
            }
        } catch (Exception e) {
            log.warn("[Health] Redis is DOWN: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName())
                    .withDetail("message", "Redis connection failed")
                    .build();
        }
    }
}
