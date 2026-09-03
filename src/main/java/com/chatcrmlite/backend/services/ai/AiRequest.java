package com.chatcrmlite.backend.services.ai;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data @Builder
public class AiRequest {
    private String prompt;
    private String systemInstruction;
    private java.util.List<dev.langchain4j.data.message.ChatMessage> messages;
    private java.util.List<dev.langchain4j.agent.tool.ToolSpecification> tools;
    private double temperature;
    private int maxTokens;
    private UUID tenantId;
    private TaskComplexity complexity;
    
    public enum TaskComplexity { LOW, MEDIUM, HIGH }
}
