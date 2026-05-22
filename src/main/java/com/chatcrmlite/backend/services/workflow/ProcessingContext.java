package com.chatcrmlite.backend.services.workflow;

import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

import java.io.Serializable;

/**
 * Shared context for a single message workflow.
 */
public class ProcessingContext implements Serializable {
    private static final long serialVersionUID = 1L;

    private String messageId;
    private String waId;
    private UUID tenantId;
    private String payload;
    private long timestamp;
    
    private Map<String, Object> metadata = new HashMap<>();
    private WorkflowStage currentStage = WorkflowStage.INGRESS;

    public ProcessingContext() {}

    public ProcessingContext(String messageId, String waId, UUID tenantId, String payload, long timestamp, Map<String, Object> metadata, WorkflowStage currentStage) {
        this.messageId = messageId;
        this.waId = waId;
        this.tenantId = tenantId;
        this.payload = payload;
        this.timestamp = timestamp;
        this.metadata = (metadata != null) ? metadata : new HashMap<>();
        this.currentStage = (currentStage != null) ? currentStage : WorkflowStage.INGRESS;
    }

    public String getMessageId() { return messageId; }
    public String getWaId() { return waId; }
    public UUID getTenantId() { return tenantId; }
    public String getPayload() { return payload; }
    public long getTimestamp() { return timestamp; }
    
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public void setWaId(String waId) { this.waId = waId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public void setPayload(String payload) { this.payload = payload; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public WorkflowStage getCurrentStage() { return currentStage; }
    public void setCurrentStage(WorkflowStage currentStage) { this.currentStage = currentStage; }
    
    public enum WorkflowStage {
        INGRESS, AI_PROCESSING, FLOW_EXECUTION, DELIVERY, COMPLETED, FAILED
    }

    public static ProcessingContextBuilder builder() {
        return new ProcessingContextBuilder();
    }

    public static class ProcessingContextBuilder {
        private String messageId;
        private String waId;
        private UUID tenantId;
        private String payload;
        private long timestamp;
        private Map<String, Object> metadata = new HashMap<>();
        private WorkflowStage currentStage = WorkflowStage.INGRESS;

        public ProcessingContextBuilder messageId(String messageId) { this.messageId = messageId; return this; }
        public ProcessingContextBuilder waId(String waId) { this.waId = waId; return this; }
        public ProcessingContextBuilder tenantId(UUID tenantId) { this.tenantId = tenantId; return this; }
        public ProcessingContextBuilder payload(String payload) { this.payload = payload; return this; }
        public ProcessingContextBuilder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
        public ProcessingContextBuilder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public ProcessingContextBuilder currentStage(WorkflowStage currentStage) { this.currentStage = currentStage; return this; }

        public ProcessingContext build() {
            return new ProcessingContext(messageId, waId, tenantId, payload, timestamp, metadata, currentStage);
        }
    }
}
