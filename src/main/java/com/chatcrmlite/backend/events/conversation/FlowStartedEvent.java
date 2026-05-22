package com.chatcrmlite.backend.events.conversation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor
public class FlowStartedEvent extends ConversationEvent {
    private String flowType;
    private UUID flowDefinitionId;
}
