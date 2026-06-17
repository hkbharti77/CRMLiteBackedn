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


