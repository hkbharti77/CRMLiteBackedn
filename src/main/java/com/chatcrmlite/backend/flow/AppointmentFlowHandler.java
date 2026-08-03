package com.chatcrmlite.backend.flow;

import com.chatcrmlite.backend.models.Appointment;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.ConversationState.FlowType;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.AppointmentRepository;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.services.AppointmentService;
import com.chatcrmlite.backend.services.GoogleCalendarService;
import com.chatcrmlite.backend.util.DateTimeParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final GoogleCalendarService googleCalendarService;
    private final AppointmentRepository appointmentRepository;
    private final ContactRepository contactRepository;

    @Override
    public boolean supports(FlowType flowType) {
        return flowType == FlowType.APPOINTMENT;
    }

    @Override
    public FlowResponse handle(FlowContext context) {
        try {
            Map<String, String> data = context.getCollectedData();
            Contact contact = context.getContact();

            // Update contact information if captured in flow and not already present
            String capturedName = data.get("name");
            if (capturedName != null && !capturedName.isBlank()) {
                if (contact.getName() == null || contact.getName().startsWith("WhatsApp User")) {
                    contact.setName(capturedName.trim());
                    contactRepository.save(contact);
                    log.info("[AppointmentFlowHandler] Updated contact name to '{}' for waId={}", 
                            capturedName, contact.getWaId());
                }
            }
            
            String capturedEmail = data.get("email");
            if (capturedEmail != null && !capturedEmail.isBlank()) {
                if (contact.getEmail() == null || contact.getEmail().isBlank()) {
                    contact.setEmail(capturedEmail.trim());
                    contactRepository.save(contact);
                    log.info("[AppointmentFlowHandler] Updated contact email to '{}' for waId={}", 
                            capturedEmail, contact.getWaId());
                }
            }

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
            // using robust multi-key extraction and natural date parsing
            LocalDateTime apptTime = DateTimeParser.extractAndParse(data);

            // Auto-generate Google Meet link if owner has connected Google Calendar
            String meetLink = null;
            User owner = context.getOwner();
            if (owner != null && owner.getGoogleAccessToken() != null && !owner.getGoogleAccessToken().isBlank()) {
                try {
                    String clientEmail = context.getContact() != null ? context.getContact().getEmail() : data.get("email");
                    String[] meetRes = googleCalendarService.createMeetLink(
                            owner,
                            title,
                            apptTime,
                            clientEmail,
                            60
                    );
                    meetLink = meetRes[0];
                    data.put("googleEventId", meetRes[1]);
                    log.info("[AppointmentFlowHandler] Auto-generated Google Meet link for WhatsApp booking: {}", meetLink);
                } catch (Exception e) {
                    log.warn("[AppointmentFlowHandler] Could not auto-generate Google Meet link: {}", e.getMessage());
                }
            }

            Appointment appt = appointmentService.bookFromFlow(
                    context.getContact(),
                    context.getOwner(),
                    title,
                    data,
                    apptTime,
                    "WHATSAPP"
            );

            if (meetLink != null && !meetLink.isBlank()) {
                appt.setMeetingLink(meetLink);
                appointmentRepository.save(appt);
            }

            log.info("[AppointmentFlowHandler] Appointment {} created for contact {} via FLOW at {}",
                    appt.getId(), context.getContact().getWaId(), apptTime);

            // Format appointment time for user
            String formattedDate = apptTime.format(DateTimeFormatter.ofPattern("dd MMM yyyy 'at' hh:mm a", Locale.ENGLISH));
            String confirmName = patientName != null && !patientName.isBlank()
                    ? patientName.split(" ")[0]
                    : null;

            StringBuilder confirmation = new StringBuilder();
            if (confirmName != null) {
                confirmation.append("✅ Thank you, ").append(confirmName).append("! Your appointment for *").append(formattedDate).append("* has been booked.");
            } else {
                confirmation.append("✅ Your appointment for *").append(formattedDate).append("* has been booked.");
            }

            if (meetLink != null && !meetLink.isBlank()) {
                confirmation.append("\n\n📹 *Google Meet Link*:\n").append(meetLink);
            } else {
                confirmation.append("\nOur team will confirm the exact details shortly.");
            }

            return FlowResponse.ok(confirmation.toString());

        } catch (Exception e) {
            log.error("[AppointmentFlowHandler] Failed to create appointment for contact {}: {}",
                    context.getContact().getWaId(), e.getMessage(), e);
            return FlowResponse.failure(e.getMessage());
        }
    }
}
