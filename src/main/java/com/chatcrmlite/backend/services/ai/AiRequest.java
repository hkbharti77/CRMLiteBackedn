package com.chatcrmlite.backend.services.ai;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data @Builder
public class AiRequest {
    private String prompt;
    private String systemInstruction;
    private double temperature;
    private int maxTokens;
    private UUID tenantId;
    private TaskComplexity complexity;
    
    public enum TaskComplexity { LOW, MEDIUM, HIGH }
}
