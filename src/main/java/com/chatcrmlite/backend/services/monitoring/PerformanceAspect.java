package com.chatcrmlite.backend.services.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class PerformanceAspect {

    private final MeterRegistry meterRegistry;

    @Value("${app.performance.threshold.db-ms:50}")
    private long dbThresholdMs;

    @Around("execution(* com.chatcrmlite.backend.repositories..*(..))")
    public Object monitorDatabaseQueries(ProceedingJoinPoint pjp) throws Throwable {
        String methodName = pjp.getSignature().getDeclaringType().getSimpleName() + "." + pjp.getSignature().getName();
        
        Timer.Sample sample = Timer.start(meterRegistry);
        long start = System.currentTimeMillis();
        try {
            return pjp.proceed();
        } finally {
            long duration = System.currentTimeMillis() - start;
            sample.stop(meterRegistry.timer("db.query.duration", "method", methodName));
            
            if (duration > dbThresholdMs) {
                log.warn("SLOW_OPERATION: Database query exceeded threshold traceId={} method={} durationMs={} thresholdMs={}", 
                         org.slf4j.MDC.get("traceId"), methodName, duration, dbThresholdMs);
            }
        }
    }
}
