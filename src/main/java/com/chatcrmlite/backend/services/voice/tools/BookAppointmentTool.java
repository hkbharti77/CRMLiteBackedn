package com.chatcrmlite.backend.services.voice.tools;

import com.chatcrmlite.backend.models.Appointment;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.AppointmentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;

@Service
public class BookAppointmentTool implements VoiceTool {

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final ContactRepository contactRepository;
    private final AppointmentService appointmentService;

    public BookAppointmentTool(ObjectMapper objectMapper, UserRepository userRepository, ContactRepository contactRepository, AppointmentService appointmentService) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.appointmentService = appointmentService;
    }

    @Override
    public String getName() {
        return "book_appointment";
    }

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name(getName())
                .description("Books an appointment for the caller.")
                .addParameter("customer_name", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("The name of the caller."))
                .addParameter("service", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("The service or reason for the appointment."))
                .addParameter("date", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("The date in YYYY-MM-DD format."))
                .addParameter("time", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("The time in HH:mm format (24-hour)."))
                .build();
    }

    @Override
    @Transactional
    public ToolExecutionResult execute(String toolCallId, String jsonArguments, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(jsonArguments);
            
            // 1. Validate Schema
            if (!args.has("customer_name") || !args.has("date") || !args.has("time")) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.VALIDATION_FAILED, "Missing required fields: customer_name, date, or time.", "VALIDATION_FAILED");
            }

            String name = args.get("customer_name").asText();
            String service = args.has("service") ? args.get("service").asText() : "Voice Appointment";
            String dateStr = args.get("date").asText();
            String timeStr = args.get("time").asText();

            User owner = userRepository.findById(context.userId()).orElse(null);
            if (owner == null) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Owner user not found.", "SYSTEM_ERROR");
            }

            // 2. Validate DateTime server-side based on tenant timezone
            String tz = (owner.getTenant() != null && owner.getTenant().getTimezone() != null) ? owner.getTenant().getTimezone() : "Asia/Kolkata";
            ZoneId zoneId = ZoneId.of(tz);

            LocalDate date;
            LocalTime time;
            try {
                date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
                time = LocalTime.parse(timeStr, DateTimeFormatter.ISO_LOCAL_TIME);
            } catch (Exception e) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.VALIDATION_FAILED, "Invalid date or time format. Use YYYY-MM-DD and HH:mm.", "INVALID_FORMAT");
            }

            LocalDateTime appointmentDateTime = LocalDateTime.of(date, time);
            
            if (appointmentDateTime.atZone(zoneId).isBefore(java.time.ZonedDateTime.now(zoneId))) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.VALIDATION_FAILED, "Cannot book appointments in the past.", "PAST_DATE");
            }

            // 3. Find or Create Contact
            String waId = context.callerPhone();
            Contact contact = contactRepository.findByWaIdAndOwner(waId, owner)
                    .orElseGet(() -> {
                        Contact newContact = Contact.builder()
                                .waId(waId)
                                .name(name)
                                .source("VOICE_BOT")
                                .owner(owner)
                                .build();
                        return contactRepository.save(newContact);
                    });

            if (contact.getName() == null || contact.getName().equals(waId)) {
                contact.setName(name);
                contact = contactRepository.save(contact);
            }

            // 4. Execute
            Appointment appt = appointmentService.bookFromFlow(contact, owner, service, new HashMap<>(), appointmentDateTime, "VOICE_BOT");

            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.SUCCESS, "Appointment booked successfully for " + dateStr + " at " + timeStr, null);
        } catch (com.chatcrmlite.backend.services.tenant.QuotaEnforcerService.QuotaExceededException e) {
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Tenant booking quota exceeded.", "QUOTA_EXCEEDED");
        } catch (Exception e) {
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.UNKNOWN, "Failed to parse arguments or execute appointment booking.", "INTERNAL_ERROR");
        }
    }
}
