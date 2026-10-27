package com.chatcrmlite.backend.services.ai;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.UUID;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.agent.tool.ToolSpecification;

@Data @Builder
public class AiRequest {
    private String prompt;
    private String systemInstruction;
    private List<ChatMessage> messages;
    private List<ToolSpecification> tools;
    private double temperature;
    private int maxTokens;
    private UUID tenantId;
    private TaskComplexity complexity;
    
    public enum TaskComplexity { LOW, MEDIUM, HIGH }

    public AiRequest() {}

    public AiRequest(String prompt, String systemInstruction, List<ChatMessage> messages, List<ToolSpecification> tools, double temperature, int maxTokens, UUID tenantId, TaskComplexity complexity) {
        this.prompt = prompt;
        this.systemInstruction = systemInstruction;
        this.messages = messages;
        this.tools = tools;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.tenantId = tenantId;
        this.complexity = complexity;
    }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getSystemInstruction() { return systemInstruction; }
    public void setSystemInstruction(String systemInstruction) { this.systemInstruction = systemInstruction; }
    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }
    public List<ToolSpecification> getTools() { return tools; }
    public void setTools(List<ToolSpecification> tools) { this.tools = tools; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public TaskComplexity getComplexity() { return complexity; }
    public void setComplexity(TaskComplexity complexity) { this.complexity = complexity; }

    public static AiRequestBuilder builder() {
        return new AiRequestBuilder();
    }

    public static class AiRequestBuilder {
        private String prompt;
        private String systemInstruction;
        private List<ChatMessage> messages;
        private List<ToolSpecification> tools;
        private double temperature;
        private int maxTokens;
        private UUID tenantId;
        private TaskComplexity complexity;

        public AiRequestBuilder prompt(String prompt) { this.prompt = prompt; return this; }
        public AiRequestBuilder systemInstruction(String systemInstruction) { this.systemInstruction = systemInstruction; return this; }
        public AiRequestBuilder messages(List<ChatMessage> messages) { this.messages = messages; return this; }
        public AiRequestBuilder tools(List<ToolSpecification> tools) { this.tools = tools; return this; }
        public AiRequestBuilder temperature(double temperature) { this.temperature = temperature; return this; }
        public AiRequestBuilder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        public AiRequestBuilder tenantId(UUID tenantId) { this.tenantId = tenantId; return this; }
        public AiRequestBuilder complexity(TaskComplexity complexity) { this.complexity = complexity; return this; }

        public AiRequest build() {
            return new AiRequest(prompt, systemInstruction, messages, tools, temperature, maxTokens, tenantId, complexity);
        }
    }
}
