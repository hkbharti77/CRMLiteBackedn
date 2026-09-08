package com.chatcrmlite.backend.services.voice.tools;

import com.chatcrmlite.backend.dto.TicketRequest;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Ticket;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.TicketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmitSupportTicketTool implements VoiceTool {

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final ContactRepository contactRepository;
    private final TicketService ticketService;

    public SubmitSupportTicketTool(ObjectMapper objectMapper, UserRepository userRepository, ContactRepository contactRepository, TicketService ticketService) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.ticketService = ticketService;
    }

    @Override
    public String getName() {
        return "submit_support_ticket";
    }

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name(getName())
                .description("Submits a support ticket or records an issue for the caller.")
                .addParameter("customer_name", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("The name of the caller."))
                .addParameter("issue_description", dev.langchain4j.agent.tool.JsonSchemaProperty.STRING, dev.langchain4j.agent.tool.JsonSchemaProperty.description("A detailed description of the issue or support request."))
                .build();
    }

    @Override
    @Transactional
    public ToolExecutionResult execute(String toolCallId, String jsonArguments, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(jsonArguments);
            
            String rawName = extractFirstNonBlank(args, "customer_name", "name", "full_name", "caller_name", "visitor_name");
            String issue = extractFirstNonBlank(args, "issue_description", "issue", "description", "details", "message", "query", "support_request");

            if (issue == null || issue.isBlank()) {
                issue = buildSummaryFromArgs(args);
            }

            if (rawName == null || rawName.isBlank()) {
                rawName = "Voice Support Request (" + (context.callerPhone() != null && !context.callerPhone().isBlank() ? context.callerPhone() : "Web") + ")";
            }

            final String name = rawName;

            User owner = userRepository.findById(context.userId())
                    .or(() -> userRepository.findFirstByTenantIdAndRole(context.tenantId(), User.Role.ADMIN))
                    .orElseGet(() -> userRepository.findAll().stream().findFirst().orElse(null));

            if (owner == null) {
                return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Owner user not found.", "SYSTEM_ERROR");
            }

            String waId = context.callerPhone() != null ? context.callerPhone() : "web_voice_" + context.callId();
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

            TicketRequest req = new TicketRequest();
            req.setSubject("Voice Support Request: " + (issue.length() > 50 ? issue.substring(0, 47) + "..." : issue));
            req.setDescription("Caller: " + name + "\nPhone: " + waId + "\n\nIssue:\n" + issue);
            req.setPriority(Ticket.TicketPriority.MEDIUM);
            req.setSource(Ticket.TicketSource.MANUAL);
            req.setContactId(contact.getId());

            Ticket ticket = ticketService.createTicket(owner, req);

            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.SUCCESS, "Support ticket created successfully. Do not speak ticket numbers to the user.", null);
        } catch (com.chatcrmlite.backend.services.tenant.QuotaEnforcerService.QuotaExceededException e) {
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.FAILED, "Tenant ticket quota exceeded.", "QUOTA_EXCEEDED");
        } catch (Exception e) {
            return new ToolExecutionResult(getName(), toolCallId, ToolExecutionStatus.UNKNOWN, "Failed to parse arguments or execute support ticket creation.", "INTERNAL_ERROR");
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

    private String buildSummaryFromArgs(JsonNode args) {
        if (args == null || !args.isObject()) return "Voice Support Request";
        StringBuilder sb = new StringBuilder();
        args.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull() && !entry.getValue().asText().isBlank()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue().asText()).append("; ");
            }
        });
        return sb.length() > 0 ? sb.toString().trim() : "Voice Support Request";
    }
}
