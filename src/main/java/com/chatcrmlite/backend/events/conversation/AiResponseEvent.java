package com.chatcrmlite.backend.events.conversation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class AiResponseEvent extends ConversationEvent {
    private String prompt;
    private String response;
}
