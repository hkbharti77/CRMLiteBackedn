package com.chatcrmlite.backend.events.conversation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Aggregate Root for a Conversation.
 * Rebuilds its state by replaying events from the stream.
 */
@Getter
@NoArgsConstructor
public class ConversationAggregate {

    private UUID id;
    private String currentState = "START";
    private String flowType;
    private Map<String, Object> collectedData = new HashMap<>();
    private int version = 0;
    private boolean completed = false;

    public ConversationAggregate(UUID id) {
        this.id = id;
    }

    /**
     * Rebuilds the state from a list of events.
     */
    public void rehydrate(List<ConversationEvent> events) {
        for (ConversationEvent event : events) {
            apply(event);
        }
    }

    /**
     * Applies a single event to the current state.
     */
    public void apply(ConversationEvent event) {
        if (event instanceof FlowStartedEvent e) {
            this.flowType = e.getFlowType();
        } else if (event instanceof StepAdvancedEvent e) {
            this.currentState = e.getToState();
            if (e.getData() != null) {
                this.collectedData.putAll(e.getData());
            }
        } else if (event instanceof FlowCompletedEvent e) {
            this.completed = true;
            if (e.getFinalData() != null) {
                this.collectedData.putAll(e.getFinalData());
            }
        }
        
        this.version = event.getVersion();
    }

    /**
     * Factory method to create a new aggregate from a stream.
     */
    public static ConversationAggregate fromEvents(UUID id, List<ConversationEvent> events) {
        ConversationAggregate aggregate = new ConversationAggregate(id);
        aggregate.rehydrate(events);
        return aggregate;
    }
}
