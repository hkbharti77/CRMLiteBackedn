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
            
            String rawName = extractFirstNonBlank(args, "customer_name", "name", "full_name", "caller_name", "visitor_name", "first_name");
            String email = extractFirstNonBlank(args, "customer_email", "email", "email_address", "mail");
            String details = extractFirstNonBlank(args, "enquiry_details", "details", "message", "enquiry", "notes", "requirements", "service", "query");

            if (details == null || details.isBlank()) {
                details = buildSummaryFromArgs(args);
            }

            if (rawName == null || rawName.isBlank()) {
                rawName = extractAnyString(args);
                if (rawName == null || rawName.isBlank()) {
                    rawName = "Voice Lead (" + (context.callerPhone() != null && !context.callerPhone().isBlank() ? context.callerPhone() : "Visitor") + ")";
                }
            }

            final String name = rawName;

            User owner = userRepository.findById(context.userId())
                    .or(() -> userRepository.findFirstByTenantIdAndRole(context.tenantId(), User.Role.ADMIN))
                    .orElseGet(() -> userRepository.findAll().stream().findFirst().orElse(null));

            if (owner == null) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Owner user not found.", "SYSTEM_ERROR");
            }

            // Find or create contact based on caller phone
            String waId = context.callerPhone() != null ? context.callerPhone() : "web_voice_" + context.callId();
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

            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.SUCCESS, "Enquiry and details submitted successfully. Do not speak lead numbers to the user.", null);

        } catch (com.chatcrmlite.backend.services.tenant.QuotaEnforcerService.QuotaExceededException e) {
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Tenant lead quota exceeded.", "QUOTA_EXCEEDED");
        } catch (Exception e) {
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.UNKNOWN, "Failed to parse arguments or internal error.", "INTERNAL_ERROR");
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

    private String extractAnyString(JsonNode args) {
        if (args == null || !args.isObject()) return null;
        var fields = args.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getValue().isNull() && !entry.getValue().asText().isBlank()) {
                return entry.getValue().asText().trim();
            }
        }
        return null;
    }

    private String buildSummaryFromArgs(JsonNode args) {
        if (args == null || !args.isObject()) return "Voice Bot Lead";
        StringBuilder sb = new StringBuilder();
        args.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull() && !entry.getValue().asText().isBlank()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue().asText()).append("; ");
            }
        });
        return sb.length() > 0 ? sb.toString().trim() : "Voice Bot Lead";
    }
}
