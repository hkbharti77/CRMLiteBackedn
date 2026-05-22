package com.chatcrmlite.backend.services.ai;

import lombok.*;
import java.util.Map;
import java.util.UUID;

/**
 * Common interface for all AI model providers (Gemini, OpenAI, etc.)
 */
public interface AiProvider {
    AiResponse generate(AiRequest request);
    String getModelName();
    boolean isHealthy();
    double getCostPer1kTokens();
}

@Data @Builder
class AiRequest {
    private String prompt;
    private String systemInstruction;
    private double temperature;
    private int maxTokens;
    private UUID tenantId;
    private TaskComplexity complexity;
    
    public enum TaskComplexity { LOW, MEDIUM, HIGH }
}

@Data @Builder
class AiResponse {
    private String content;
    private int tokensUsed;
    private long latencyMs;
    private String provider;
    private Map<String, Object> metadata;
}
