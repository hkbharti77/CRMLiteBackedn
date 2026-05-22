package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Ticket;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Fired when a public (non-internal) comment is added to a Ticket.
 *
 * Consumers: EmailNotificationListener
 */
@Getter
public class TicketCommentAddedEvent extends ApplicationEvent {

    private final Ticket ticket;
    private final String commentMessage;
    private final String authorName;

    public TicketCommentAddedEvent(Object publisher, Ticket ticket,
                                   String commentMessage, String authorName) {
        super(publisher);
        this.ticket = ticket;
        this.commentMessage = commentMessage;
        this.authorName = authorName;
    }
}
