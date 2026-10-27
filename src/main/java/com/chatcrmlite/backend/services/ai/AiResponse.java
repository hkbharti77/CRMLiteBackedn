package com.chatcrmlite.backend.services.ai;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

@Data @Builder
public class AiResponse {
    private String content;
    private int tokensUsed;
    private long latencyMs;
    private String provider;
    private Map<String, Object> metadata;
    private List<ToolExecutionRequest> toolExecutionRequests;

    public AiResponse() {}

    public AiResponse(String content, int tokensUsed, long latencyMs, String provider, Map<String, Object> metadata, List<ToolExecutionRequest> toolExecutionRequests) {
        this.content = content;
        this.tokensUsed = tokensUsed;
        this.latencyMs = latencyMs;
        this.provider = provider;
        this.metadata = metadata;
        this.toolExecutionRequests = toolExecutionRequests;
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public int getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public List<ToolExecutionRequest> getToolExecutionRequests() { return toolExecutionRequests; }
    public void setToolExecutionRequests(List<ToolExecutionRequest> toolExecutionRequests) { this.toolExecutionRequests = toolExecutionRequests; }

    public static AiResponseBuilder builder() {
        return new AiResponseBuilder();
    }

    public static class AiResponseBuilder {
        private String content;
        private int tokensUsed;
        private long latencyMs;
        private String provider;
        private Map<String, Object> metadata;
        private List<ToolExecutionRequest> toolExecutionRequests;

        public AiResponseBuilder content(String content) { this.content = content; return this; }
        public AiResponseBuilder tokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; return this; }
        public AiResponseBuilder latencyMs(long latencyMs) { this.latencyMs = latencyMs; return this; }
        public AiResponseBuilder provider(String provider) { this.provider = provider; return this; }
        public AiResponseBuilder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public AiResponseBuilder toolExecutionRequests(List<ToolExecutionRequest> toolExecutionRequests) { this.toolExecutionRequests = toolExecutionRequests; return this; }

        public AiResponse build() {
            return new AiResponse(content, tokensUsed, latencyMs, provider, metadata, toolExecutionRequests);
        }
    }
}
