package com.chatcrmlite.backend.cqrs.commands;

import lombok.Data;
import java.util.UUID;

/**
 * Command to start a new conversation flow.
 */
@Data
public class StartConversationCommand implements Command {
    private final UUID conversationId;
    private final UUID tenantId;
    private final String waId;
    private final String flowType;
}
