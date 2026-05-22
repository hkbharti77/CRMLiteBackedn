package com.chatcrmlite.backend.flow;

import com.chatcrmlite.backend.models.Booking;
import com.chatcrmlite.backend.models.ConversationState.FlowType;
import com.chatcrmlite.backend.services.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * FlowHandler for BOOKING flows.
 *
 * Responsible for:
 * 1. Creating a Booking from the collected flow data
 * 2. Publishing a BookingConfirmedEvent (delegated to BookingService)
 * 3. Returning the user-facing confirmation message
 *
 * The handler is deliberately thin — all business logic lives in BookingService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingFlowHandler implements FlowHandler {

    private final BookingService bookingService;

    @Override
    public boolean supports(FlowType flowType) {
        return flowType == FlowType.BOOKING;
    }

    @Override
    public FlowResponse handle(FlowContext context) {
        try {
            Map<String, String> data = context.getCollectedData();

            // Extract service name — covers all niche-specific booking keys
            String service = data.getOrDefault("service",
                             data.getOrDefault("occasion",        // makeup artists
                             data.getOrDefault("class_type",      // yoga/meditation
                             data.getOrDefault("shoot_type",      // photographers
                             data.getOrDefault("goal",            // gym/fitness
                             data.getOrDefault("class",
                             data.getOrDefault("session", "Booking")))))));

            // Extract slot — covers date_time, event_date, and preferred_slot keys
            String slot = data.getOrDefault("date_time",
                          data.getOrDefault("event_date",         // makeup, photographers
                          data.getOrDefault("preferred_slot",     // gym, yoga
                          data.get("slot"))));

            // Enrich service label with session type or fitness level if available
            String sessionType  = data.get("session_type");   // gym
            String experienceLevel = data.get("experience_level"); // yoga
            String sessionMode  = data.get("session_mode");   // yoga
            if (sessionType != null && !sessionType.isBlank()) {
                service = service + " — " + sessionType;
            } else if (experienceLevel != null && !experienceLevel.isBlank()) {
                service = service + " (" + experienceLevel + ")";
            }
            if (sessionMode != null && !sessionMode.isBlank()) {
                service = service + " [" + sessionMode + "]";
            }

            Booking booking = bookingService.bookFromFlow(
                    context.getContact(),
                    context.getOwner(),
                    service,
                    slot,
                    data
            );

            log.info("[BookingFlowHandler] Booking {} created for contact {} via FLOW",
                    booking.getId(), context.getContact().getWaId());

            // Build a richer confirmation message
            String confirmation = buildConfirmation(data, service, slot);
            return FlowResponse.ok(confirmation);

        } catch (Exception e) {
            log.error("[BookingFlowHandler] Failed to create booking for contact {}: {}",
                    context.getContact().getWaId(), e.getMessage(), e);
            return FlowResponse.failure(e.getMessage());
        }
    }

    /**
     * Builds a personalised confirmation message using collected data.
     */
    private String buildConfirmation(Map<String, String> data, String service, String slot) {
        StringBuilder msg = new StringBuilder("🎉 *Booking Confirmed!*\n\n");
        msg.append("📌 *").append(service).append("*\n");
        if (slot != null && !slot.isBlank()) {
            msg.append("📅 ").append(slot).append("\n");
        }
        String location = data.get("location");
        if (location != null && !location.isBlank()) {
            msg.append("📍 ").append(location).append("\n");
        }
        msg.append("\nWe've reserved your slot. Our team will reach out to confirm details shortly. See you soon! 😊");
        return msg.toString();
    }
}
