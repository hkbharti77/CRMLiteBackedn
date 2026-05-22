package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Ticket;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Fired when a Ticket's status changes.
 *
 * Consumers: EmailNotificationListener
 */
@Getter
public class TicketStatusChangedEvent extends ApplicationEvent {

    private final Ticket ticket;
    private final Ticket.TicketStatus oldStatus;
    private final Ticket.TicketStatus newStatus;

    public TicketStatusChangedEvent(Object publisher, Ticket ticket,
                                    Ticket.TicketStatus oldStatus,
                                    Ticket.TicketStatus newStatus) {
        super(publisher);
        this.ticket = ticket;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }
}
