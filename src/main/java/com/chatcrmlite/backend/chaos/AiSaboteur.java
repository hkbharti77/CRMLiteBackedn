package com.chatcrmlite.backend.chaos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.stereotype.Component;

/**
 * Aspect to inject AI failures for Chaos Engineering experiments.
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AiSaboteur {

    private final ChaosMonkeyService chaosMonkey;

    @Around("execution(* com.chatcrmlite.backend.services.ai.AiOrchestrator.execute(..))")
    public Object sabotageAi(ProceedingJoinPoint pjp) throws Throwable {
        
        // 1. Simulate Outage
        if (chaosMonkey.isExperimentActive("AI_TOTAL_OUTAGE")) {
            log.error("💥 [Chaos-Saboteur] Simulating total AI provider outage!");
            throw new RuntimeException("Service Unavailable: AI Providers Down");
        }

        // 2. Simulate Latency
        if (chaosMonkey.isExperimentActive("AI_LATENCY_SPIKE")) {
            log.warn("🐢 [Chaos-Saboteur] Injecting artificial AI latency...");
            chaosMonkey.simulateLatency("AI_LATENCY_SPIKE");
        }

        return pjp.proceed();
    }
}
