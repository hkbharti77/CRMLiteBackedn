package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Ticket;
import com.chatcrmlite.backend.models.User;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Fired when a Ticket is assigned to an agent.
 *
 * Consumers: EmailNotificationListener
 */
@Getter
public class TicketAssignedEvent extends ApplicationEvent {

    private final Ticket ticket;
    private final User agent;

    public TicketAssignedEvent(Object publisher, Ticket ticket, User agent) {
        super(publisher);
        this.ticket = ticket;
        this.agent = agent;
    }
}
