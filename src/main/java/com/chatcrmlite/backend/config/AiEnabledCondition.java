package com.chatcrmlite.backend.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class AiEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String provider = context.getEnvironment().getProperty("ai.provider", "gemini").toLowerCase();

        if ("none".equals(provider)) {
            return false;
        }
        if ("openrouter".equals(provider)) {
            String key = context.getEnvironment().getProperty("ai.openrouter.api-key");
            return key != null && !key.isBlank() && !key.startsWith("dummy");
        }
        if ("openai".equals(provider)) {
            String key = context.getEnvironment().getProperty("ai.openai.api-key");
            return key != null && !key.isBlank() && !key.startsWith("dummy");
        }
        if ("ollama".equals(provider) || "local".equals(provider)) {
            String baseUrl = context.getEnvironment().getProperty("ai.openai.base-url");
            return baseUrl != null && !baseUrl.isBlank();
        }
        
        // Default (gemini)
        String key = context.getEnvironment().getProperty("langchain4j.google-ai.gemini.api-key");
        return key != null && !key.isBlank() && !key.startsWith("dummy");
    }
}
