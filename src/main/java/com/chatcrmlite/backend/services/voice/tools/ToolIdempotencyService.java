package com.chatcrmlite.backend.services.voice.tools;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class ToolIdempotencyService {

    // Simple cache to prevent duplicate executions for the same tool call in the same session.
    // Key format: "tenantId:conversationId:turnId:toolCallId"
    private final Cache<String, ToolExecutionResult> idempotencyCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(10_000)
            .build();

    public String generateKey(ToolExecutionContext context, String toolCallId) {
        return String.format("%s:%s:%s:%s", 
                context.tenantId(), 
                context.conversationId(), 
                context.turnId(), 
                toolCallId);
    }

    public ToolExecutionResult getCachedResult(String idempotencyKey) {
        return idempotencyCache.getIfPresent(idempotencyKey);
    }

    public void cacheResult(String idempotencyKey, ToolExecutionResult result) {
        idempotencyCache.put(idempotencyKey, result);
    }
}
