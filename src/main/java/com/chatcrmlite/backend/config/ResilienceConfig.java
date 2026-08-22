package com.chatcrmlite.backend.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Production Resilience4j configuration using Registry beans.
 *
 * TimeLimiter and Bulkhead instances are configured via application.properties:
 *   resilience4j.timelimiter.instances.geminiAi.timeout-duration=10s
 *   resilience4j.bulkhead.instances.geminiAi.max-concurrent-calls=5
 *
 * To apply on service methods, use annotations:
 *   @CircuitBreaker(name = "geminiAi", fallbackMethod = "fallback")
 *   @Retry(name = "geminiAi")
 *   @TimeLimiter(name = "geminiAi")
 *   @Bulkhead(name = "geminiAi", type = Bulkhead.Type.SEMAPHORE)
 */
@Configuration
public class ResilienceConfig {

    // ── Circuit Breaker Configs ───────────────────────────────────────────────

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig geminiConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(40)
                .slowCallRateThreshold(60)
                .slowCallDurationThreshold(Duration.ofSeconds(8))
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreakerConfig whatsAppConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .permittedNumberOfCallsInHalfOpenState(3)
                .minimumNumberOfCalls(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreakerConfig ragConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(30)
                .waitDurationInOpenState(Duration.ofSeconds(45))
                .slidingWindowSize(15)
                .minimumNumberOfCalls(10)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker("geminiAi", geminiConfig);
        registry.circuitBreaker("whatsAppClient", whatsAppConfig);
        registry.circuitBreaker("ragRetrieval", ragConfig);

        return registry;
    }

    // ── Retry Configs ─────────────────────────────────────────────────────────

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig geminiRetry = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(2))
                .retryExceptions(IOException.class, SocketTimeoutException.class)
                .ignoreExceptions(IllegalArgumentException.class)
                .build();

        RetryConfig whatsAppRetry = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(500))
                .retryOnException(throwable -> {
                    if (throwable instanceof HttpStatusCodeException hsce) {
                        return hsce.getStatusCode().is5xxServerError() || hsce.getStatusCode().value() == 429;
                    }
                    return throwable instanceof IOException 
                        || throwable instanceof SocketTimeoutException
                        || throwable instanceof ResourceAccessException;
                })
                .ignoreExceptions(IllegalArgumentException.class)
                .build();

        RetryConfig ragRetry = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofSeconds(1))
                .build();

        RetryRegistry registry = RetryRegistry.ofDefaults();
        registry.retry("geminiAi", geminiRetry);
        registry.retry("whatsAppClient", whatsAppRetry);
        registry.retry("ragRetrieval", ragRetry);

        return registry;
    }
}
