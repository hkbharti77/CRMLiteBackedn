package com.chatcrmlite.backend.services.voice.tools;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.ReferenceNumberService;
import com.chatcrmlite.backend.services.lead.LeadEnquiryService;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class CreateLeadTool implements VoiceTool {

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final ContactRepository contactRepository;
    private final LeadRepository leadRepository;
    private final QuotaEnforcerService quotaEnforcerService;
    private final ReferenceNumberService referenceNumberService;
    private final LeadEnquiryService leadEnquiryService;

    public CreateLeadTool(ObjectMapper objectMapper, UserRepository userRepository, ContactRepository contactRepository, LeadRepository leadRepository, QuotaEnforcerService quotaEnforcerService, ReferenceNumberService referenceNumberService, LeadEnquiryService leadEnquiryService) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.leadRepository = leadRepository;
        this.quotaEnforcerService = quotaEnforcerService;
        this.referenceNumberService = referenceNumberService;
        this.leadEnquiryService = leadEnquiryService;
    }

    @Override
    public String getName() {
        return "create_lead";
    }

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name(getName())
                .description("Creates a new lead for a prospective customer. Use this when the caller wants to express interest, leave their details for a callback, or submit an enquiry.")
                .addParameter("customer_name", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("The name of the caller."))
                .addParameter("customer_email", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("The email of the caller (optional)."))
                .addParameter("enquiry_details", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("Details of what they are inquiring about."))
                .build();
    }

    @Override
    @Transactional
    public ToolExecutionResult execute(String toolCallId, String jsonArguments, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(jsonArguments);
            String name = args.has("customer_name") ? args.get("customer_name").asText() : null;
            String email = args.has("customer_email") ? args.get("customer_email").asText() : null;
            String details = args.has("enquiry_details") ? args.get("enquiry_details").asText() : "Voice Bot Lead";

            if (name == null || name.isBlank()) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.VALIDATION_FAILED, "Missing required field: customer_name", "VALIDATION_FAILED");
            }

            User owner = userRepository.findById(context.userId()).orElse(null);
            if (owner == null) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Owner user not found.", "SYSTEM_ERROR");
            }

            // Find or create contact based on caller phone
            String waId = context.callerPhone();
            Contact contact = contactRepository.findByWaIdAndOwner(waId, owner)
                    .orElseGet(() -> {
                        Contact newContact = Contact.builder()
                                .waId(waId)
                                .email(email)
                                .name(name)
                                .source("VOICE_BOT")
                                .owner(owner)
                                .build();
                        return contactRepository.save(newContact);
                    });

            // If existing contact, update email/name if missing
            boolean contactUpdated = false;
            if ((contact.getName() == null || contact.getName().equals(waId)) && name != null) {
                contact.setName(name);
                contactUpdated = true;
            }
            if (contact.getEmail() == null && email != null) {
                contact.setEmail(email);
                contactUpdated = true;
            }
            if (contactUpdated) {
                contact = contactRepository.save(contact);
            }

            // Create lead
            quotaEnforcerService.verifyLeadQuota(context.tenantId());
            String leadNumber = referenceNumberService.generate(owner, ReferenceNumberService.EntityType.LEAD);

            Lead lead = Lead.builder()
                    .leadNumber(leadNumber)
                    .contact(contact)
                    .owner(owner)
                    .status(Lead.LeadStatus.NEW)
                    .build();

            Lead savedLead = leadRepository.save(lead);
            
            // Append enquiry
            Map<String, String> data = new HashMap<>();
            data.put("phone", waId);
            if (email != null) data.put("email", email);
            data.put("name", name);
            
            leadEnquiryService.appendEnquiry(savedLead, details, "VOICE_BOT", "voice-bot", data);

            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.SUCCESS, "Lead created successfully. Lead Number: " + leadNumber, null);

        } catch (com.chatcrmlite.backend.services.tenant.QuotaEnforcerService.QuotaExceededException e) {
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Tenant lead quota exceeded.", "QUOTA_EXCEEDED");
        } catch (Exception e) {
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.UNKNOWN, "Failed to parse arguments or internal error.", "INTERNAL_ERROR");
        }
    }
}
