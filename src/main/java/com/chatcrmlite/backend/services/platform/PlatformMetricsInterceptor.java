package com.chatcrmlite.backend.services.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Intercepts incoming HTTP requests to increment daily API and AI counters in Redis.
 * Used for Platform Analytics.
 */
@Component
public class PlatformMetricsInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;

    public PlatformMetricsInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        
        // Skip static assets, health checks, etc.
        if (uri == null || !uri.startsWith("/api/")) {
            return true;
        }

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        
        // Increment general API requests
        String apiKey = "platform:api_requests:" + today;
        redisTemplate.opsForValue().increment(apiKey);
        redisTemplate.expire(apiKey, java.time.Duration.ofDays(2));

        // If it's an AI endpoint, increment AI requests
        if (uri.contains("/ai/") || uri.contains("/rag/") || uri.contains("/bot/")) {
            String aiKey = "platform:ai_requests:" + today;
            redisTemplate.opsForValue().increment(aiKey);
            redisTemplate.expire(aiKey, java.time.Duration.ofDays(2));
        }

        return true;
    }
}
