package com.chatcrmlite.backend.services.voice.tools;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.ReferenceNumberService;
import com.chatcrmlite.backend.services.FlowConfigService;
import com.chatcrmlite.backend.services.lead.LeadEnquiryService;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class CreateLeadTool implements VoiceTool {

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final ContactRepository contactRepository;
    private final LeadRepository leadRepository;
    private final QuotaEnforcerService quotaEnforcerService;
    private final ReferenceNumberService referenceNumberService;
    private final LeadEnquiryService leadEnquiryService;
    private final FlowConfigService flowConfigService;

    public CreateLeadTool(ObjectMapper objectMapper, UserRepository userRepository, ContactRepository contactRepository, LeadRepository leadRepository, QuotaEnforcerService quotaEnforcerService, ReferenceNumberService referenceNumberService, LeadEnquiryService leadEnquiryService, FlowConfigService flowConfigService) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.leadRepository = leadRepository;
        this.quotaEnforcerService = quotaEnforcerService;
        this.referenceNumberService = referenceNumberService;
        this.leadEnquiryService = leadEnquiryService;
        this.flowConfigService = flowConfigService;
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

            // 1. Validate required dynamic steps configured by tenant in DB
            if (flowConfigService != null) {
                try {
                    com.chatcrmlite.backend.dto.FlowConfigDTO flowConfig = flowConfigService.getFlowConfig(finalOwner, "lead");
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
                                } else if (key.contains("email")) {
                                    val = extractFirstNonBlank(args, key, "customer_email", "email");
                                } else if (key.contains("enquiry") || key.contains("detail") || key.contains("requirement") || key.contains("message")) {
                                    val = extractFirstNonBlank(args, key, "enquiry_details", "details", "message", "enquiry", "specific_requirement");
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
                    log.warn("[CreateLeadTool] Dynamic flow validation skipped due to: {}", ex.getMessage());
                }
            }
            
            String rawName = extractFirstNonBlank(args, "customer_name", "name", "full_name", "caller_name", "visitor_name", "first_name");
            String email = extractFirstNonBlank(args, "customer_email", "email", "email_address", "mail");
            String details = extractFirstNonBlank(args, "enquiry_details", "details", "message", "enquiry", "notes", "requirements", "service", "query", "specific_requirement");
            
            String effectivePhone = hasTelephonyPhone 
                    ? context.callerPhone() 
                    : (phoneFromArgs != null && !phoneFromArgs.isBlank() ? phoneFromArgs : null);

            if (rawName == null || rawName.trim().isEmpty() || rawName.equalsIgnoreCase("unknown")) {
                rawName = "Voice Caller (" + (effectivePhone != null ? effectivePhone : "Web") + ")";
            }
            if (details == null || details.trim().isEmpty() || details.equalsIgnoreCase("unknown")) {
                details = "Voice Lead Enquiry";
            }

            final String name = rawName;

            // Find or create contact based on effective phone
            String waId = effectivePhone != null ? effectivePhone : "web_voice_" + context.callId();
            Contact contact = contactRepository.findByWaIdAndOwner(waId, finalOwner)
                    .orElseGet(() -> {
                        Contact newContact = Contact.builder()
                                .waId(waId)
                                .email(email)
                                .name(name)
                                .source(hasTelephonyPhone ? "VOICE_BOT" : "WEBSITE_VOICE_BOT")
                                .owner(finalOwner)
                                .build();
                        return contactRepository.save(newContact);
                    });

            // If existing contact, update email/name if missing
            boolean contactUpdated = false;
            if ((contact.getName() == null || contact.getName().equals(waId) || contact.getName().startsWith("Voice Caller")) && name != null) {
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
            String leadNumber = referenceNumberService.generate(finalOwner, ReferenceNumberService.EntityType.LEAD);

            Lead lead = Lead.builder()
                    .leadNumber(leadNumber)
                    .contact(contact)
                    .owner(finalOwner)
                    .status(Lead.LeadStatus.NEW)
                    .build();

            Lead savedLead = leadRepository.save(lead);
            
            // Append enquiry with all collected dynamic fields
            Map<String, String> data = new HashMap<>();
            java.util.Iterator<String> fieldNames = args.fieldNames();
            while (fieldNames.hasNext()) {
                String f = fieldNames.next();
                JsonNode node = args.get(f);
                if (node != null && !node.isNull() && !node.asText().isBlank() && !node.asText().equalsIgnoreCase("unknown")) {
                    data.put(f, node.asText().trim());
                }
            }

            // Safely truncate phone to fit in 255 character limit
            String safePhone = waId.length() > 250 ? waId.substring(0, 250) : waId;
            data.put("phone", safePhone);
            if (email != null && !email.equalsIgnoreCase("unknown")) data.put("email", email);
            if (name != null) data.put("name", name);
            
            leadEnquiryService.appendEnquiry(savedLead, details, "VOICE_BOT", "voice-bot", data);

            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.SUCCESS, "Lead created successfully.", null);

        } catch (com.chatcrmlite.backend.services.tenant.QuotaEnforcerService.QuotaExceededException e) {
            log.warn("Quota exceeded in CreateLeadTool: {}", e.getMessage());
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Tenant lead quota exceeded.", "QUOTA_EXCEEDED");
        } catch (Exception e) {
            log.error("Exception in CreateLeadTool execute", e);
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.UNKNOWN, "Failed to parse arguments or internal error: " + e.getMessage(), "INTERNAL_ERROR");
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
