package com.chatcrmlite.backend.models.memory;

import lombok.Data;
import java.util.Map;
import java.util.HashMap;
import java.time.Instant;

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
    private Instant lastActivity;

    public ConversationSessionState() {}

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getCurrentIntent() { return currentIntent; }
    public void setCurrentIntent(String currentIntent) { this.currentIntent = currentIntent; }
    public String getActiveTopic() { return activeTopic; }
    public void setActiveTopic(String activeTopic) { this.activeTopic = activeTopic; }
    public Map<String, Object> getSlots() { return slots; }
    public void setSlots(Map<String, Object> slots) { this.slots = slots; }
    public String getWorkflowState() { return workflowState; }
    public void setWorkflowState(String workflowState) { this.workflowState = workflowState; }
    public String getRunningSummary() { return runningSummary; }
    public void setRunningSummary(String runningSummary) { this.runningSummary = runningSummary; }
    public int getSummaryVersion() { return summaryVersion; }
    public void setSummaryVersion(int summaryVersion) { this.summaryVersion = summaryVersion; }
    public Instant getLastActivity() { return lastActivity; }
    public void setLastActivity(Instant lastActivity) { this.lastActivity = lastActivity; }
}
