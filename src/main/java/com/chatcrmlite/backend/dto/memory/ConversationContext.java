package com.chatcrmlite.backend.dto.memory;

import com.chatcrmlite.backend.models.memory.ConversationSessionState;
import lombok.Builder;
import lombok.Data;

/**
 * The full memory context passed to the RagRetrievalService and PromptBuilder.
 */
@Data
@Builder
public class ConversationContext {
    private String formattedRecentTurns; // Chronological representation of recent messages
    private String rollingSummary;       // Long-term summarization (if any)
    private ConversationSessionState sessionState; // Structured entities and intent
    private boolean requiresRag;         // Determined by RagRouterService
    private String latestQuery;          // The user's current query

    public ConversationContext() {}

    public ConversationContext(String formattedRecentTurns, String rollingSummary, ConversationSessionState sessionState, boolean requiresRag, String latestQuery) {
        this.formattedRecentTurns = formattedRecentTurns;
        this.rollingSummary = rollingSummary;
        this.sessionState = sessionState;
        this.requiresRag = requiresRag;
        this.latestQuery = latestQuery;
    }

    public String getFormattedRecentTurns() { return formattedRecentTurns; }
    public void setFormattedRecentTurns(String formattedRecentTurns) { this.formattedRecentTurns = formattedRecentTurns; }
    public String getRollingSummary() { return rollingSummary; }
    public void setRollingSummary(String rollingSummary) { this.rollingSummary = rollingSummary; }
    public ConversationSessionState getSessionState() { return sessionState; }
    public void setSessionState(ConversationSessionState sessionState) { this.sessionState = sessionState; }
    public boolean isRequiresRag() { return requiresRag; }
    public void setRequiresRag(boolean requiresRag) { this.requiresRag = requiresRag; }
    public String getLatestQuery() { return latestQuery; }
    public void setLatestQuery(String latestQuery) { this.latestQuery = latestQuery; }

    public static ConversationContextBuilder builder() {
        return new ConversationContextBuilder();
    }

    public static class ConversationContextBuilder {
        private String formattedRecentTurns;
        private String rollingSummary;
        private ConversationSessionState sessionState;
        private boolean requiresRag;
        private String latestQuery;

        public ConversationContextBuilder formattedRecentTurns(String formattedRecentTurns) {
            this.formattedRecentTurns = formattedRecentTurns;
            return this;
        }

        public ConversationContextBuilder rollingSummary(String rollingSummary) {
            this.rollingSummary = rollingSummary;
            return this;
        }

        public ConversationContextBuilder sessionState(ConversationSessionState sessionState) {
            this.sessionState = sessionState;
            return this;
        }

        public ConversationContextBuilder requiresRag(boolean requiresRag) {
            this.requiresRag = requiresRag;
            return this;
        }

        public ConversationContextBuilder latestQuery(String latestQuery) {
            this.latestQuery = latestQuery;
            return this;
        }

        public ConversationContext build() {
            return new ConversationContext(formattedRecentTurns, rollingSummary, sessionState, requiresRag, latestQuery);
        }
    }
}
