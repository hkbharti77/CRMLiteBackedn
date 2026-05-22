package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Appointment;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Fired when an Appointment is scheduled — either from WhatsApp flow or manual creation.
 *
 * Consumers: ActivityLogListener, (future) ReminderService, NotificationService
 */
@Getter
public class AppointmentScheduledEvent extends ApplicationEvent {

    private final Appointment appointment;
    private final String source; // "FLOW" | "MANUAL" | "API"

    public AppointmentScheduledEvent(Object publisher, Appointment appointment, String source) {
        super(publisher);
        this.appointment = appointment;
        this.source = source;
    }
}
