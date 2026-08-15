package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;


/**
 * Tracks the multi-step WhatsApp conversation flow for each contact.
 * One active state per contact at a time.
 */
@Entity
@Table(name = "conversation_states")
public class ConversationState implements Serializable {
    private static final long serialVersionUID = 1L;


    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Contact whose flow is being tracked ─────────────────────────────────
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false, unique = true)
    private Contact contact;

    // ── Flow metadata ────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlowType flowType;   // APPOINTMENT, BOOKING, ENQUIRY, LEAD_CAPTURE

    @Column(name = "current_state", nullable = false)
    private String currentState = "START";

    @Column(name = "flow_definition_id")
    private UUID flowDefinitionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_history", columnDefinition = "jsonb")
    private String stateHistory = "[]";

    // AP-9 fix: Changed from plain TEXT to JSONB for JSON path query support.
    // GIN index added in V10031 (idx_conv_collected_data).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "collected_data", columnDefinition = "jsonb")
    private String collectedData = "{}";

    // ── Session Timeout Fields ───────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "session_status", nullable = false)
    private SessionStatus sessionStatus = SessionStatus.ACTIVE;

    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt = LocalDateTime.now();

    @Column(name = "timeout_started_at")
    private LocalDateTime timeoutStartedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "close_reason")
    private String closeReason;

    @Column(name = "previous_state")
    private String previousState;

    // ── Timestamps ───────────────────────────────────────────────────────────
    private LocalDateTime startedAt = LocalDateTime.now();
    private LocalDateTime lastUpdatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        lastUpdatedAt = LocalDateTime.now();
    }

    public ConversationState() {}

    public ConversationState(UUID id, Contact contact, FlowType flowType, String currentState, UUID flowDefinitionId, String stateHistory, String collectedData, LocalDateTime startedAt, LocalDateTime lastUpdatedAt) {
        this.id = id;
        this.contact = contact;
        this.flowType = flowType;
        this.currentState = (currentState != null) ? currentState : "START";
        this.flowDefinitionId = flowDefinitionId;
        this.stateHistory = (stateHistory != null) ? stateHistory : "[]";
        this.collectedData = (collectedData != null) ? collectedData : "{}";
        this.startedAt = (startedAt != null) ? startedAt : LocalDateTime.now();
        this.lastUpdatedAt = (lastUpdatedAt != null) ? lastUpdatedAt : LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public Contact getContact() { return contact; }
    public FlowType getFlowType() { return flowType; }
    public String getCurrentState() { return currentState; }
    public UUID getFlowDefinitionId() { return flowDefinitionId; }
    public String getStateHistory() { return stateHistory; }
    public String getCollectedData() { return collectedData; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getLastUpdatedAt() { return lastUpdatedAt; }
    public SessionStatus getSessionStatus() { return sessionStatus; }
    public LocalDateTime getLastActivityAt() { return lastActivityAt; }
    public LocalDateTime getTimeoutStartedAt() { return timeoutStartedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public String getCloseReason() { return closeReason; }
    public String getPreviousState() { return previousState; }

    public void setId(UUID id) { this.id = id; }
    public void setContact(Contact contact) { this.contact = contact; }
    public void setFlowType(FlowType flowType) { this.flowType = flowType; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }
    public void setFlowDefinitionId(UUID flowDefinitionId) { this.flowDefinitionId = flowDefinitionId; }
    public void setStateHistory(String stateHistory) { this.stateHistory = stateHistory; }
    public void setCollectedData(String collectedData) { this.collectedData = collectedData; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public void setLastUpdatedAt(LocalDateTime lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
    public void setSessionStatus(SessionStatus sessionStatus) { this.sessionStatus = sessionStatus; }
    public void setLastActivityAt(LocalDateTime lastActivityAt) { this.lastActivityAt = lastActivityAt; }
    public void setTimeoutStartedAt(LocalDateTime timeoutStartedAt) { this.timeoutStartedAt = timeoutStartedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public void setCloseReason(String closeReason) { this.closeReason = closeReason; }
    public void setPreviousState(String previousState) { this.previousState = previousState; }

    // ── Flow Types ───────────────────────────────────────────────────────────
    public enum FlowType {
        APPOINTMENT, BOOKING, ENQUIRY, LEAD_CAPTURE, SUPPORT
    }

    public static ConversationStateBuilder builder() {
        return new ConversationStateBuilder();
    }

    public static class ConversationStateBuilder {
        private UUID id;
        private Contact contact;
        private FlowType flowType;
        private String currentState;
        private UUID flowDefinitionId;
        private String stateHistory;
        private String collectedData;
        private LocalDateTime startedAt;
        private LocalDateTime lastUpdatedAt;
        private SessionStatus sessionStatus;
        private LocalDateTime lastActivityAt;
        private LocalDateTime timeoutStartedAt;
        private LocalDateTime closedAt;
        private String closeReason;
        private String previousState;

        public ConversationStateBuilder id(UUID id) { this.id = id; return this; }
        public ConversationStateBuilder contact(Contact contact) { this.contact = contact; return this; }
        public ConversationStateBuilder flowType(FlowType flowType) { this.flowType = flowType; return this; }
        public ConversationStateBuilder currentState(String currentState) { this.currentState = currentState; return this; }
        public ConversationStateBuilder flowDefinitionId(UUID flowDefinitionId) { this.flowDefinitionId = flowDefinitionId; return this; }
        public ConversationStateBuilder stateHistory(String stateHistory) { this.stateHistory = stateHistory; return this; }
        public ConversationStateBuilder collectedData(String collectedData) { this.collectedData = collectedData; return this; }
        public ConversationStateBuilder startedAt(LocalDateTime startedAt) { this.startedAt = startedAt; return this; }
        public ConversationStateBuilder lastUpdatedAt(LocalDateTime lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; return this; }
        public ConversationStateBuilder sessionStatus(SessionStatus sessionStatus) { this.sessionStatus = sessionStatus; return this; }
        public ConversationStateBuilder lastActivityAt(LocalDateTime lastActivityAt) { this.lastActivityAt = lastActivityAt; return this; }
        public ConversationStateBuilder timeoutStartedAt(LocalDateTime timeoutStartedAt) { this.timeoutStartedAt = timeoutStartedAt; return this; }
        public ConversationStateBuilder closedAt(LocalDateTime closedAt) { this.closedAt = closedAt; return this; }
        public ConversationStateBuilder closeReason(String closeReason) { this.closeReason = closeReason; return this; }
        public ConversationStateBuilder previousState(String previousState) { this.previousState = previousState; return this; }

        public ConversationState build() {
            ConversationState state = new ConversationState(id, contact, flowType, currentState, flowDefinitionId, stateHistory, collectedData, startedAt, lastUpdatedAt);
            if (sessionStatus != null) state.setSessionStatus(sessionStatus);
            if (lastActivityAt != null) state.setLastActivityAt(lastActivityAt);
            if (timeoutStartedAt != null) state.setTimeoutStartedAt(timeoutStartedAt);
            if (closedAt != null) state.setClosedAt(closedAt);
            if (closeReason != null) state.setCloseReason(closeReason);
            if (previousState != null) state.setPreviousState(previousState);
            return state;
        }
    }
}
