package com.chatcrmlite.backend.models.memory;

import lombok.Data;
import java.util.Map;
import java.util.HashMap;

/**
 * Represents the structured conversational state extracted from conversation history.
 */
@Data
public class ConversationSessionState {
    private String conversationId;
    private String channel;
    private String tenantId;
    private String language;
    
    // Structured Context extracted via LLM/Router
    private String currentIntent;
    private String activeTopic;
    private Map<String, Object> slots = new HashMap<>(); // Extracted entities (e.g. date, service)
    private String workflowState;
    
    // Background Summarization
    private String runningSummary;
    private int summaryVersion;
    private java.time.Instant lastActivity;
}
