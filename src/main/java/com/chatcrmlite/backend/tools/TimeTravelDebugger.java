package com.chatcrmlite.backend.tools;

import com.chatcrmlite.backend.events.conversation.ConversationAggregate;
import com.chatcrmlite.backend.events.conversation.ConversationEvent;
import com.chatcrmlite.backend.events.conversation.ConversationEventStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Utility to inspect conversation state at any point in history.
 */
@Component
@RequiredArgsConstructor
public class TimeTravelDebugger {

    private final ConversationEventStore eventStore;

    /**
     * Reconstructs the state of a conversation as it was at a specific time.
     */
    public ConversationAggregate viewAt(UUID conversationId, LocalDateTime pointInTime) {
        List<ConversationEvent> events = eventStore.getEvents(conversationId);
        
        List<ConversationEvent> filteredEvents = events.stream()
                .filter(e -> e.getTimestamp().isBefore(pointInTime) || e.getTimestamp().isEqual(pointInTime))
                .collect(Collectors.toList());

        return ConversationAggregate.fromEvents(conversationId, filteredEvents);
    }

    /**
     * Reconstructs the state at a specific version.
     */
    public ConversationAggregate viewAtVersion(UUID conversationId, int version) {
        List<ConversationEvent> events = eventStore.getEvents(conversationId);
        
        List<ConversationEvent> filteredEvents = events.stream()
                .filter(e -> e.getVersion() <= version)
                .collect(Collectors.toList());

        return ConversationAggregate.fromEvents(conversationId, filteredEvents);
    }
}
