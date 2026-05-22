package com.chatcrmlite.backend.flow;

import com.chatcrmlite.backend.models.Appointment;
import com.chatcrmlite.backend.models.ConversationState.FlowType;
import com.chatcrmlite.backend.services.AppointmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * FlowHandler for APPOINTMENT flows.
 *
 * Responsible for:
 * 1. Creating an Appointment from the collected flow data
 * 2. Publishing an AppointmentScheduledEvent (delegated to AppointmentService)
 * 3. Returning the user-facing confirmation message
 *
 * The handler is deliberately thin — all business logic lives in AppointmentService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentFlowHandler implements FlowHandler {

    private final AppointmentService appointmentService;

    @Override
    public boolean supports(FlowType flowType) {
        return flowType == FlowType.APPOINTMENT;
    }

    @Override
    public FlowResponse handle(FlowContext context) {
        try {
            Map<String, String> data = context.getCollectedData();

            // Extract appointment title — covers all niche-specific keys
            String title = data.getOrDefault("treatment",
                           data.getOrDefault("consultation_type",   // homeopathy/ayurveda
                           data.getOrDefault("concern",             // physiotherapy
                           data.getOrDefault("skin_concern",        // skin & aesthetic
                           data.getOrDefault("service",
                           data.getOrDefault("goal", "Appointment"))))));

            // Enrich title with patient name if captured
            String patientName = data.get("patient_name");
            if (patientName != null && !patientName.isBlank()) {
                title = title + " — " + patientName;
            }

            // Parse appointment datetime from collected data if available,
            // otherwise default to tomorrow at 10AM
            LocalDateTime apptTime = parseDateTime(data.getOrDefault("date_time", ""));

            Appointment appt = appointmentService.bookFromFlow(
                    context.getContact(),
                    context.getOwner(),
                    title,
                    data,
                    apptTime
            );

            log.info("[AppointmentFlowHandler] Appointment {} created for contact {} via FLOW",
                    appt.getId(), context.getContact().getWaId());

            // Personalise confirmation with patient name if available
            String confirmName = patientName != null && !patientName.isBlank()
                    ? patientName.split(" ")[0]
                    : null;
            String confirmation = confirmName != null
                    ? "✅ Thank you, " + confirmName + "! Your appointment has been booked. Our team will confirm the exact time shortly."
                    : "✅ Your appointment has been booked! Our team will confirm the exact time shortly.";

            return FlowResponse.ok(confirmation);

        } catch (Exception e) {
            log.error("[AppointmentFlowHandler] Failed to create appointment for contact {}: {}",
                    context.getContact().getWaId(), e.getMessage(), e);
            return FlowResponse.failure(e.getMessage());
        }
    }

    /**
     * Attempts to extract a meaningful LocalDateTime from the free-text date_time field.
     * Falls back to tomorrow at 10AM if parsing fails or field is empty.
     */
    private LocalDateTime parseDateTime(String raw) {
        // Default: tomorrow at 10AM
        LocalDateTime fallback = LocalDateTime.now().plusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        if (raw == null || raw.isBlank()) return fallback;

        // Try ISO format first (yyyy-MM-ddTHH:mm)
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception ignored) {}

        // Try date-only ISO (yyyy-MM-dd) → set to 10AM
        try {
            return java.time.LocalDate.parse(raw).atTime(10, 0);
        } catch (Exception ignored) {}

        // Could not parse — return fallback and let staff confirm manually
        log.warn("[AppointmentFlowHandler] Could not parse date_time='{}', using fallback tomorrow 10AM", raw);
        return fallback;
    }
}
