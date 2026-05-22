package com.chatcrmlite.backend.events.conversation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.Map;

@Getter @Setter @NoArgsConstructor
public class StepAdvancedEvent extends ConversationEvent {
    private String fromState;
    private String toState;
    private Map<String, Object> data;
}
