package com.chatcrmlite.backend.chaos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Utility to inject Redis failures.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RedisSaboteur {

    private final ChaosMonkeyService chaosMonkey;

    public void guard() {
        // 1. Connection Failure
        if (chaosMonkey.isExperimentActive("REDIS_CONNECTION_FAILURE")) {
            log.error("💥 [Chaos-Saboteur] Simulating Redis connection failure!");
            throw new org.springframework.data.redis.RedisConnectionFailureException("Simulated Failure");
        }

        // 2. Latency
        if (chaosMonkey.isExperimentActive("REDIS_LATENCY")) {
            chaosMonkey.simulateLatency("REDIS_LATENCY");
        }
    }
}
