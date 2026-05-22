package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.Booking;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Fired when a Booking is created/confirmed — either from WhatsApp flow or manual creation.
 *
 * Consumers: ActivityLogListener, (future) NotificationService, ReminderService
 */
@Getter
public class BookingConfirmedEvent extends ApplicationEvent {

    private final Booking booking;
    private final String source; // "FLOW" | "MANUAL" | "API"

    public BookingConfirmedEvent(Object publisher, Booking booking, String source) {
        super(publisher);
        this.booking = booking;
        this.source = source;
    }
}
