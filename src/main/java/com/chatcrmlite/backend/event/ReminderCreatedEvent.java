package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Reminder;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Fired when a Reminder is created in the CRM.
 * Consumed by GoogleIntegrationWorker to asynchronously push task to Google Tasks.
 */
@Getter
public class ReminderCreatedEvent extends ApplicationEvent {

    private final Reminder reminder;

    public ReminderCreatedEvent(Object publisher, Reminder reminder) {
        super(publisher);
        this.reminder = reminder;
    }
}
