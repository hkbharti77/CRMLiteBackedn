package com.chatcrmlite.backend.event;

import com.chatcrmlite.backend.models.ActivityLog;
import com.chatcrmlite.backend.services.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Unified CRM Event Listener — writes every domain event to the Activity Log.
 *
 * Architecture:
 *   Domain Service → ApplicationEventPublisher.publishEvent(...)
 *              → ActivityLogListener.on*() [async, isolated]
 *              → ActivityLogService.log*()
 *              → activity_logs table
 *
 * Benefits:
 * - Domain services have NO dependency on ActivityLogService
 * - Failures in logging never roll back the main business transaction
 * - New event types can be added by adding a new @EventListener method here
 * - Easy to add more listeners (NotificationService, AnalyticsService) without
 *   touching any existing code
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityLogListener {

    private final ActivityLogService activityLogService;

    // ── Lead Events ────────────────────────────────────────────────────────

    @Async
    @EventListener
    public void onLeadCreated(LeadCreatedEvent event) {
        log.debug("[EventBus] LeadCreatedEvent received for lead={}", event.getLead().getId());
        activityLogService.logLeadCreated(event.getLead(), event.getSource());
    }

    // ── Booking Events ─────────────────────────────────────────────────────

    @Async
    @EventListener
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        log.debug("[EventBus] BookingConfirmedEvent received for booking={}", event.getBooking().getId());
        activityLogService.logBookingConfirmed(event.getBooking(), event.getSource());
    }

    // ── Appointment Events ─────────────────────────────────────────────────

    @Async
    @EventListener
    public void onAppointmentScheduled(AppointmentScheduledEvent event) {
        log.debug("[EventBus] AppointmentScheduledEvent received for appointment={}", event.getAppointment().getId());
        activityLogService.logAppointmentScheduled(event.getAppointment(), event.getSource());
    }
}
