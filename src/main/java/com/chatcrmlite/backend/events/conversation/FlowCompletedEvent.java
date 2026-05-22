package com.chatcrmlite.backend.events.conversation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.Map;

@Getter @Setter @NoArgsConstructor
public class FlowCompletedEvent extends ConversationEvent {
    private String outcome;
    private Map<String, Object> finalData;
}
