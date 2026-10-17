package com.chatcrmlite.backend.services.voice.tools;

import com.chatcrmlite.backend.models.Booking;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.BookingService;
import com.chatcrmlite.backend.services.FlowConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class CreateBookingTool implements VoiceTool {

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final ContactRepository contactRepository;
    private final BookingService bookingService;
    private final FlowConfigService flowConfigService;

    public CreateBookingTool(ObjectMapper objectMapper, UserRepository userRepository, ContactRepository contactRepository, BookingService bookingService, FlowConfigService flowConfigService) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.bookingService = bookingService;
        this.flowConfigService = flowConfigService;
    }

    @Override
    public String getName() {
        return "create_booking";
    }

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name(getName())
                .description("Creates a booking for an event, class, or service.")
                .addParameter("customer_name", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("The name of the caller."))
                .addParameter("service", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("The service to book."))
                .addParameter("preferred_slot", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("The preferred date/time slot string."))
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

            // 1. Dynamic validation against tenant-configured flow steps
            if (flowConfigService != null) {
                try {
                    com.chatcrmlite.backend.dto.FlowConfigDTO flowConfig = flowConfigService.getFlowConfig(finalOwner, "booking");
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
                                } else if (key.contains("phone") || key.contains("mobile")) {
                                    val = (phoneFromArgs != null && !phoneFromArgs.isBlank()) ? phoneFromArgs : (hasTelephonyPhone ? context.callerPhone() : null);
                                } else if (key.contains("service") || key.contains("item") || key.contains("package")) {
                                    val = extractFirstNonBlank(args, key, "service", "item", "package", "service_category");
                                } else if (key.contains("date") && !key.contains("time")) {
                                    val = extractFirstNonBlank(args, key, "preferred_date", "date", "service_date");
                                } else if (key.contains("time") || key.contains("slot")) {
                                    val = extractFirstNonBlank(args, key, "preferred_contact_time", "preferred_time", "time", "slot", "preferred_slot");
                                } else if (key.contains("city") || key.contains("address") || key.contains("location")) {
                                    val = extractFirstNonBlank(args, key, "city", "location", "address");
                                } else if (key.contains("requirement") || key.contains("detail") || key.contains("enquiry")) {
                                    val = extractFirstNonBlank(args, key, "specific_requirement", "requirement", "details");
                                }

                                if (val == null || val.trim().isEmpty() || val.equalsIgnoreCase("unknown")) {
                                    String question = (step.getQuestion() != null && !step.getQuestion().isBlank())
                                            ? step.getQuestion()
                                            : "Could you please specify your " + key.replace("_", " ") + "?";
                                    return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.VALIDATION_FAILED,
                                            "Missing required field '" + key + "'. Please ask the caller: " + question, "MISSING_FIELD");
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("[CreateBookingTool] Dynamic flow validation skipped due to: {}", ex.getMessage());
                }
            }
            
            String rawName = extractFirstNonBlank(args, "customer_name", "name", "full_name", "caller_name");
            String service = extractFirstNonBlank(args, "service", "service_name", "item", "package", "type");
            String preferredSlot = extractFirstNonBlank(args, "preferred_slot", "slot", "date", "time", "date_time", "preferred_date");

            String effectivePhone = hasTelephonyPhone 
                    ? context.callerPhone() 
                    : (phoneFromArgs != null && !phoneFromArgs.isBlank() ? phoneFromArgs : null);

            if (rawName == null || rawName.trim().isEmpty() || rawName.equalsIgnoreCase("unknown")) {
                rawName = "Voice Caller (" + (effectivePhone != null ? effectivePhone : "Web") + ")";
            }
            if (service == null || service.trim().isEmpty() || service.equalsIgnoreCase("unknown")) {
                service = "General Booking";
            }
            if (preferredSlot == null || preferredSlot.trim().isEmpty() || preferredSlot.equalsIgnoreCase("unknown")) {
                preferredSlot = "As soon as possible";
            }

            final String name = rawName;
            String waId = effectivePhone != null ? effectivePhone : "web_voice_" + context.callId();
            Contact contact = contactRepository.findByWaIdAndOwner(waId, finalOwner)
                    .orElseGet(() -> {
                        Contact newContact = Contact.builder()
                                .waId(waId)
                                .name(name)
                                .source(hasTelephonyPhone ? "VOICE_BOT" : "WEBSITE_VOICE_BOT")
                                .owner(finalOwner)
                                .build();
                        return contactRepository.save(newContact);
                    });

            if (contact.getName() == null || contact.getName().equals(waId) || contact.getName().startsWith("Voice Caller")) {
                contact.setName(name);
                contact = contactRepository.save(contact);
            }

            // Collect all dynamic fields from LLM arguments
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
            flowData.put("service", service);
            flowData.put("preferred_slot", preferredSlot);
            flowData.put("phone", waId);

            Booking booking = bookingService.bookFromFlow(contact, finalOwner, service, preferredSlot, flowData, "VOICE_BOT");

            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.SUCCESS, "Booking created successfully. Reference: " + booking.getReferenceNumber(), null);
        } catch (com.chatcrmlite.backend.services.tenant.QuotaEnforcerService.QuotaExceededException e) {
            log.warn("Quota exceeded in CreateBookingTool: {}", e.getMessage());
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Tenant booking quota exceeded.", "QUOTA_EXCEEDED");
        } catch (Exception e) {
            log.error("Exception in CreateBookingTool execute", e);
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.UNKNOWN, "Failed to parse arguments or execute booking: " + e.getMessage(), "INTERNAL_ERROR");
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
