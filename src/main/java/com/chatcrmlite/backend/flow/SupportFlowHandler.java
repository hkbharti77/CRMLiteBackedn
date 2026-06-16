package com.chatcrmlite.backend.flow;

import com.chatcrmlite.backend.dto.TicketRequest;
import com.chatcrmlite.backend.models.ConversationState;
import com.chatcrmlite.backend.services.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import com.chatcrmlite.backend.repositories.SupportFormConfigRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class SupportFlowHandler implements FlowHandler {

    private final TicketService ticketService;
    private final SupportFormConfigRepository supportFormConfigRepository;

    @Override
    public boolean supports(ConversationState.FlowType flowType) {
        return flowType == ConversationState.FlowType.SUPPORT;
    }

    @Override
    public FlowResponse handle(FlowContext context) {
        try {
            Map<String, String> data = context.getCollectedData();

            TicketRequest req = new TicketRequest();
            req.setContactId(context.getContact().getId());
            req.setSubmitterName(data.getOrDefault("name", context.getContact().getName()));
            req.setSubmitterEmail(data.get("email"));
            req.setSubmitterPhone(data.getOrDefault("phone", context.getContact().getWaId()));
            req.setSubject(data.getOrDefault("subject", "WhatsApp Support Request"));
            req.setDescription(data.getOrDefault("message", data.getOrDefault("issue", "No description provided")));
            req.setCategory(data.get("category"));
            req.setSource(com.chatcrmlite.backend.models.Ticket.TicketSource.WHATSAPP);

            ticketService.createTicket(context.getOwner(), req);

            String successMsg = "✅ Thank you! We have received your support request and a ticket has been created. Our team will get back to you shortly.";
            var optConfig = supportFormConfigRepository.findByOwner(context.getOwner());
            if (optConfig.isPresent() && optConfig.get().getSuccessMessage() != null && !optConfig.get().getSuccessMessage().isBlank()) {
                successMsg = optConfig.get().getSuccessMessage();
            }

            return FlowResponse.ok(successMsg);
        } catch (Exception e) {
            log.error("Failed to process support flow", e);
            return FlowResponse.failure("Failed to process your request. Please try again later.");
        }
    }
}
