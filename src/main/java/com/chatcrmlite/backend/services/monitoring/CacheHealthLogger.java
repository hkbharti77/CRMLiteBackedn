package com.chatcrmlite.backend.services.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheHealthLogger {

    private final MeterRegistry meterRegistry;

    /**
     * Logs cache health metrics every hour.
     */
    @Scheduled(fixedRateString = "PT1H")
    public void logCacheHealth() {
        log.info("=== Cache Health Report ===");
        try {
            meterRegistry.find("cache.gets").meters().forEach(meter -> {
                String cacheName = meter.getId().getTag("name");
                String result = meter.getId().getTag("result"); // hit or miss
                double count = meter.measure().iterator().next().getValue();
                log.info("Cache: {} | Result: {} | Count: {}", cacheName, result, count);
            });

            meterRegistry.find("cache.evictions").meters().forEach(meter -> {
                String cacheName = meter.getId().getTag("name");
                double evictions = meter.measure().iterator().next().getValue();
                log.info("Cache: {} | Evictions: {}", cacheName, evictions);
            });
        } catch (Exception e) {
            log.warn("Failed to generate cache health report", e);
        }
        log.info("===========================");
    }
}
