package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Lead;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Fired when a Lead is created — either from a WhatsApp conversational flow
 * or a manual CRM action.
 *
 * Consumers: ActivityLogListener, (future) NotificationService, AnalyticsService
 */
@Getter
public class LeadCreatedEvent extends ApplicationEvent {

    private final Lead lead;
    private final String source; // "FLOW" | "MANUAL" | "API"

    public LeadCreatedEvent(Object publisher, Lead lead, String source) {
        super(publisher);
        this.lead = lead;
        this.source = source;
    }
}
