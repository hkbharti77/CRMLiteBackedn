package com.chatcrmlite.backend.config.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@RequiredArgsConstructor
public class PrometheusMetricsConfig {

    private final StringRedisTemplate redisTemplate;

    @Bean
    public MeterBinder redisStreamMetrics() {
        return registry -> {
            // Monitor Ingress Stream Depth
            registry.gauge("workflow.stream.depth", 
                io.micrometer.core.instrument.Tags.of("stream", "ingress"), 
                redisTemplate, 
                rt -> getStreamLength("whatsapp:ingress"));

            // Monitor AI Stream Depth
            registry.gauge("workflow.stream.depth", 
                io.micrometer.core.instrument.Tags.of("stream", "ai"), 
                redisTemplate, 
                rt -> getStreamLength("workflow:ai:free") + getStreamLength("workflow:ai:pro"));

            // Monitor Flow Stream Depth
            registry.gauge("workflow.stream.depth", 
                io.micrometer.core.instrument.Tags.of("stream", "flow"), 
                redisTemplate, 
                rt -> getStreamLength("workflow:flow:free") + getStreamLength("workflow:flow:pro"));
        };
    }

    private long getStreamLength(String streamName) {
        try {
            Long size = redisTemplate.opsForStream().size(streamName);
            return size != null ? size : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
