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
}
