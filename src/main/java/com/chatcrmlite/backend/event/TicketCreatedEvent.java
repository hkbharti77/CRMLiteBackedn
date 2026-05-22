package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Ticket;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Fired when a Ticket is created — from support form, manual creation, or WhatsApp.
 *
 * Consumers: ActivityLogListener, EmailNotificationListener
 */
@Getter
public class TicketCreatedEvent extends ApplicationEvent {

    private final Ticket ticket;
    private final String source; // "SUPPORT_FORM" | "MANUAL" | "WHATSAPP"

    public TicketCreatedEvent(Object publisher, Ticket ticket, String source) {
        super(publisher);
        this.ticket = ticket;
        this.source = source;
    }
}
