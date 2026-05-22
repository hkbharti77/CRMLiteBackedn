package com.chatcrmlite.backend.services;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Monitors and traces distributed job executions.
 * Provides metrics for Prometheus/Grafana.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobCoordinator {

    private final MeterRegistry meterRegistry;

    /**
     * Records the result of a distributed job execution.
     */
    public void recordExecution(String jobName, long durationMs, boolean success) {
        String result = success ? "success" : "failure";
        
        Timer.builder("job.execution.time")
                .tag("job", jobName)
                .tag("status", result)
                .description("Time taken to execute a distributed job")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        meterRegistry.counter("job.execution.count", 
                "job", jobName, 
                "status", result
        ).increment();
        
        if (!success) {
            log.error("🚨 DISTRIBUTED_JOB_FAILURE: {} failed after {}ms", jobName, durationMs);
            // In a production system, this could trigger a Slack or PagerDuty alert via a global exception handler or log appender
        } else {
            log.debug("✅ Distributed job {} completed in {}ms", jobName, durationMs);
        }
    }
}
