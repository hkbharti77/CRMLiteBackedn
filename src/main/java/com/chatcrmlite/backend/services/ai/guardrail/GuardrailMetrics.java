package com.chatcrmlite.backend.services.ai.guardrail;

import com.chatcrmlite.backend.dto.ai.Decision;
import com.chatcrmlite.backend.services.RedisStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class GuardrailMetrics {

    private final RedisStateService redisStateService;

    public void recordMetrics(Decision decision, long latency, UUID tenantId) {
        long minute = Instant.now().getEpochSecond() / 60;
        if (decision == Decision.CALL_AI) {
            String hitKey = "global:ai:hits:" + minute;
            Long currentHits = redisStateService.increment(hitKey, Duration.ofMinutes(2));
            
            if (currentHits != null && currentHits > 50) {
                triggerAlert("AI_SPIKE");
            }
        }
    }

    private void triggerAlert(String type) {
        String alertKey = "global:alert:" + type;
        if (redisStateService.tryLock(alertKey, Duration.ofMinutes(1))) {
            log.warn("PRODUCTION ALERT: {} detected!", type);
        }
    }
}
