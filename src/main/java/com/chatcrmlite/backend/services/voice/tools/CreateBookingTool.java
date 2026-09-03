package com.chatcrmlite.backend.services.voice.tools;

import com.chatcrmlite.backend.models.Booking;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.BookingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;

@Service
public class CreateBookingTool implements VoiceTool {

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final ContactRepository contactRepository;
    private final BookingService bookingService;

    public CreateBookingTool(ObjectMapper objectMapper, UserRepository userRepository, ContactRepository contactRepository, BookingService bookingService) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.bookingService = bookingService;
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
            
            if (!args.has("customer_name") || !args.has("service") || !args.has("preferred_slot")) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.VALIDATION_FAILED, "Missing required fields: customer_name, service, preferred_slot.", "VALIDATION_FAILED");
            }

            String name = args.get("customer_name").asText();
            String service = args.get("service").asText();
            String preferredSlot = args.get("preferred_slot").asText();

            User owner = userRepository.findById(context.userId()).orElse(null);
            if (owner == null) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Owner user not found.", "SYSTEM_ERROR");
            }

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

            Booking booking = bookingService.bookFromFlow(contact, owner, service, preferredSlot, new HashMap<>(), "VOICE_BOT");

            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.SUCCESS, "Booking created successfully.", null);
        } catch (com.chatcrmlite.backend.services.tenant.QuotaEnforcerService.QuotaExceededException e) {
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Tenant booking quota exceeded.", "QUOTA_EXCEEDED");
        } catch (Exception e) {
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.UNKNOWN, "Failed to parse arguments or execute booking.", "INTERNAL_ERROR");
        }
    }
}
