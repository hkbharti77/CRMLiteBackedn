package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Lead;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Fired when a Lead's status changes.
 *
 * Consumers: EmailNotificationListener
 */
@Getter
public class LeadStatusChangedEvent extends ApplicationEvent {

    private final Lead lead;
    private final Lead.LeadStatus oldStatus;
    private final Lead.LeadStatus newStatus;

    public LeadStatusChangedEvent(Object publisher, Lead lead,
                                  Lead.LeadStatus oldStatus,
                                  Lead.LeadStatus newStatus) {
        super(publisher);
        this.lead = lead;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }
}
