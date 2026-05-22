package com.chatcrmlite.backend.events.conversation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MessageReceivedEvent.class, name = "MessageReceived"),
    @JsonSubTypes.Type(value = FlowStartedEvent.class, name = "FlowStarted"),
    @JsonSubTypes.Type(value = StepAdvancedEvent.class, name = "StepAdvanced"),
    @JsonSubTypes.Type(value = AiResponseEvent.class, name = "AIResponseGenerated"),
    @JsonSubTypes.Type(value = FlowCompletedEvent.class, name = "FlowCompleted")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public abstract class ConversationEvent {
    private UUID eventId = UUID.randomUUID();
    private UUID conversationId;
    private LocalDateTime timestamp = LocalDateTime.now();
    private int version;

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
