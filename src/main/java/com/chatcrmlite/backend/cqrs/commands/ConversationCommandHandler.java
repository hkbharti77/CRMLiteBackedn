package com.chatcrmlite.backend.cqrs.commands;

import com.chatcrmlite.backend.events.conversation.ConversationEventStore;
import com.chatcrmlite.backend.events.conversation.FlowStartedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConversationCommandHandler implements CommandHandler<StartConversationCommand> {

    private final ConversationEventStore eventStore;

    @Override
    public void handle(StartConversationCommand command) {
        // 1. Create Domain Event
        FlowStartedEvent event = new FlowStartedEvent();
        event.setConversationId(command.getConversationId());
        event.setFlowType(command.getFlowType());
        event.setVersion(1); // Initial version
        
        // 2. Persist to Event Store
        eventStore.append(event);
        
        // 3. (Optional) Publish to internal bus for real-time projections
    }
}
