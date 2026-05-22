package com.chatcrmlite.backend.events.conversation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class MessageReceivedEvent extends ConversationEvent {
    private String text;
    private String waMessageId;
}
