package com.chatcrmlite.backend.services.voice.tools;

import com.chatcrmlite.backend.models.Appointment;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.AppointmentService;
import com.chatcrmlite.backend.services.FlowConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class BookAppointmentTool implements VoiceTool {

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final ContactRepository contactRepository;
    private final AppointmentService appointmentService;
    private final FlowConfigService flowConfigService;

    public BookAppointmentTool(ObjectMapper objectMapper, UserRepository userRepository, ContactRepository contactRepository, AppointmentService appointmentService, FlowConfigService flowConfigService) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.appointmentService = appointmentService;
        this.flowConfigService = flowConfigService;
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

            User owner = null;
            if (context.userId() != null) {
                owner = userRepository.findById(context.userId()).orElse(null);
            }
            if (owner == null && context.tenantId() != null) {
                owner = userRepository.findFirstByTenantIdAndRole(context.tenantId(), User.Role.ADMIN)
                        .or(() -> userRepository.findFirstUserIdByTenantId(context.tenantId()).flatMap(userRepository::findById))
                        .orElse(null);
            }
            if (owner == null) {
                owner = userRepository.findAll().stream().findFirst().orElse(null);
            }

            if (owner == null) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Owner user not found.", "SYSTEM_ERROR");
            }
            final User finalOwner = owner;

            boolean hasTelephonyPhone = context != null 
                    && context.callerPhone() != null 
                    && !context.callerPhone().isBlank() 
                    && !context.callerPhone().contains("9999999999") 
                    && !context.callerPhone().startsWith("web_");

            String phoneFromArgs = extractFirstNonBlank(args, "phone", "mobile", "whatsapp", "contact_number", "phone_number", "contact");

            // 1. Dynamic validation against tenant-configured appointment flow steps
            if (flowConfigService != null) {
                try {
                    com.chatcrmlite.backend.dto.FlowConfigDTO flowConfig = flowConfigService.getFlowConfig(finalOwner, "appointment");
                    if (flowConfig != null && flowConfig.getSteps() != null) {
                        for (com.chatcrmlite.backend.dto.FlowStepDTO step : flowConfig.getSteps()) {
                            if (step.getDataKey() == null || step.getDataKey().isBlank()) continue;
                            String key = step.getDataKey().trim();
                            // If phone is already captured via Exotel or WhatsApp Caller ID, skip phone validation
                            if (("phone".equals(key) || "mobile".equals(key) || "whatsapp".equals(key)) && hasTelephonyPhone) {
                                continue;
                            }

                            if (step.isRequired()) {
                                String val = extractFirstNonBlank(args, key, key.replace("_", ""), key.toLowerCase());
                                if (key.contains("name")) {
                                    val = extractFirstNonBlank(args, key, "customer_name", "name", "full_name", "caller_name");
                                } else if (key.contains("phone") || key.contains("mobile") || key.contains("contact")) {
                                    val = (phoneFromArgs != null && !phoneFromArgs.isBlank()) ? phoneFromArgs : (hasTelephonyPhone ? context.callerPhone() : null);
                                } else if (key.contains("date") && !key.contains("time")) {
                                    val = extractFirstNonBlank(args, key, "date", "appointment_date", "preferred_date");
                                } else if (key.contains("time")) {
                                    val = extractFirstNonBlank(args, key, "time", "appointment_time", "preferred_time", "slot");
                                } else if (key.contains("service") || key.contains("reason") || key.contains("title")) {
                                    val = extractFirstNonBlank(args, key, "service", "reason", "title");
                                }

                                if (val == null || val.trim().isEmpty() || val.equalsIgnoreCase("unknown")) {
                                    String question = (step.getQuestion() != null && !step.getQuestion().isBlank())
                                            ? step.getQuestion()
                                            : "Could you please provide your " + key.replace("_", " ") + "?";
                                    return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.VALIDATION_FAILED,
                                            "Missing required field '" + key + "'. Please ask the caller: " + question, "MISSING_FIELD");
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("[BookAppointmentTool] Dynamic flow validation skipped due to: {}", ex.getMessage());
                }
            }

            // 2. Validate essential fields
            String name = extractFirstNonBlank(args, "customer_name", "name", "full_name", "caller_name");
            String service = extractFirstNonBlank(args, "service", "reason", "title", "department");
            String dateStr = extractFirstNonBlank(args, "date", "appointment_date", "preferred_date");
            String timeStr = extractFirstNonBlank(args, "time", "appointment_time", "preferred_time", "slot");

            String effectivePhone = hasTelephonyPhone 
                    ? context.callerPhone() 
                    : (phoneFromArgs != null && !phoneFromArgs.isBlank() ? phoneFromArgs : null);

            if (name == null || name.isBlank()) {
                name = "Voice Caller (" + (effectivePhone != null ? effectivePhone : "Web") + ")";
            }
            final String contactName = name;

            if (service == null || service.isBlank()) {
                service = "Voice Appointment";
            }
            if (dateStr == null || dateStr.isBlank() || timeStr == null || timeStr.isBlank()) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.VALIDATION_FAILED,
                        "Please ask the caller what date and time they prefer for the appointment.", "VALIDATION_FAILED");
            }

            // 3. Validate DateTime server-side based on tenant timezone
            String tz = (finalOwner.getTenant() != null && finalOwner.getTenant().getTimezone() != null) ? finalOwner.getTenant().getTimezone() : "Asia/Kolkata";
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
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.VALIDATION_FAILED, "Cannot book appointments in the past. Please suggest an upcoming date and time.", "PAST_DATE");
            }

            // 4. Find or Create Contact
            String waId = effectivePhone != null ? effectivePhone : "web_voice_" + context.callId();
            Contact contact = contactRepository.findByWaIdAndOwner(waId, finalOwner)
                    .orElseGet(() -> {
                        Contact newContact = Contact.builder()
                                .waId(waId)
                                .name(contactName)
                                .source(hasTelephonyPhone ? "VOICE_BOT" : "WEBSITE_VOICE_BOT")
                                .owner(finalOwner)
                                .build();
                        return contactRepository.save(newContact);
                    });

            if (contact.getName() == null || contact.getName().equals(waId) || contact.getName().startsWith("Voice Caller")) {
                contact.setName(contactName);
                contact = contactRepository.save(contact);
            }

            // 5. Collect all dynamic fields from LLM arguments
            Map<String, String> flowData = new HashMap<>();
            java.util.Iterator<String> fieldNames = args.fieldNames();
            while (fieldNames.hasNext()) {
                String f = fieldNames.next();
                JsonNode node = args.get(f);
                if (node != null && !node.isNull() && !node.asText().isBlank() && !node.asText().equalsIgnoreCase("unknown")) {
                    flowData.put(f, node.asText().trim());
                }
            }
            flowData.put("name", name);
            flowData.put("date", dateStr);
            flowData.put("time", timeStr);
            flowData.put("title", service);
            flowData.put("phone", waId);

            // 6. Execute booking
            Appointment appt = appointmentService.bookFromFlow(contact, owner, service, flowData, appointmentDateTime, "VOICE_BOT");

            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.SUCCESS, "Appointment booked successfully for " + dateStr + " at " + timeStr + ". Reference: " + appt.getReferenceNumber(), null);
        } catch (com.chatcrmlite.backend.services.tenant.QuotaEnforcerService.QuotaExceededException e) {
            log.warn("Quota exceeded in BookAppointmentTool: {}", e.getMessage());
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Tenant booking quota exceeded.", "QUOTA_EXCEEDED");
        } catch (Exception e) {
            log.error("Exception in BookAppointmentTool execute", e);
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.UNKNOWN, "Failed to parse arguments or execute appointment booking: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private String extractFirstNonBlank(JsonNode args, String... keys) {
        if (args == null) return null;
        for (String k : keys) {
            if (args.has(k) && !args.get(k).isNull() && !args.get(k).asText().isBlank()) {
                return args.get(k).asText().trim();
            }
        }
        return null;
    }
}
