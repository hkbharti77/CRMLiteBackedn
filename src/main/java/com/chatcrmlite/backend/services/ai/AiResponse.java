package com.chatcrmlite.backend.services.ai;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data @Builder
public class AiResponse {
    private String content;
    private int tokensUsed;
    private long latencyMs;
    private String provider;
    private Map<String, Object> metadata;
}
