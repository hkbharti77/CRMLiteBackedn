package com.chatcrmlite.backend.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Health indicator for Meta WhatsApp Graph API reachability.
 * Uses a lightweight OPTIONS-style check against the public API endpoint.
 * Exposed at /actuator/health/metaApi
 */
@Slf4j
@Component("metaApi")
@RequiredArgsConstructor
public class MetaApiHealthIndicator implements HealthIndicator {

    private static final String META_HEALTH_URL = "https://graph.facebook.com/v20.0/";

    private final RestTemplate restTemplate;

    @Override
    public Health health() {
        try {
            long start = System.currentTimeMillis();
            // A lightweight HEAD/GET to the Graph API root — returns 200 or 400
            // even without auth; the important thing is network reachability
            restTemplate.headForHeaders(META_HEALTH_URL);
            long latencyMs = System.currentTimeMillis() - start;

            return Health.up()
                    .withDetail("url", META_HEALTH_URL)
                    .withDetail("latency_ms", latencyMs)
                    .build();
        } catch (Exception e) {
            log.warn("[Health] Meta Graph API unreachable: {}", e.getMessage());
            return Health.down()
                    .withDetail("url", META_HEALTH_URL)
                    .withDetail("error", e.getClass().getSimpleName())
                    .withDetail("message", "Meta API connectivity check failed")
                    .build();
        }
    }
}
