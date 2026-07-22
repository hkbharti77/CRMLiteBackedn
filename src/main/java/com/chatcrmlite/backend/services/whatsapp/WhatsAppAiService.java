package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.RagRetrievalService;
import com.chatcrmlite.backend.services.ai.guardrail.GuardrailService;
import com.chatcrmlite.backend.dto.ai.Decision;
import com.chatcrmlite.backend.dto.ai.GuardrailResult;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppAiService {

    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final ContactRepository contactRepository;
    private final MessageRepository messageRepository;
    private final GuardrailService guardrailService;
    private final RagRetrievalService ragRetrievalService;

    @Transactional
    public void evaluateAiIntake(ProcessingContext context) {
        try {
            WhatsAppConfig config = whatsappConfigRepository.findByTenantId(context.getTenantId())
                    .orElseThrow(() -> new RuntimeException("Config not found"));
            User owner = config.getUser();
            Contact contact = contactRepository.findByWaIdAndOwner(context.getWaId(), owner)
                    .orElseThrow(() -> new RuntimeException("Contact not found"));

            String text = (String) context.getMetadata().get("text");
            if (text == null) {
                text = "";
            }

            // Check if last response was AI for context boost
            PageRequest pageRequest = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "timestamp"));
            List<Message> lastOutgoing = messageRepository.findByContactAndDirection(contact,
                    Message.Direction.OUTGOING, pageRequest);
            boolean lastWasAi = !lastOutgoing.isEmpty()
                    && !lastOutgoing.get(0).getContent().contains("[Sent Interactive Menu]");

            GuardrailResult guardrail = guardrailService.evaluate(text, contact.getWaId(), lastWasAi,
                    owner.getBusinessSubType(), owner.getId());

            switch (guardrail.getDecision()) {
                case GREETING:
                    context.getMetadata().put("responseType", "GREETING");
                    break;
                case CALL_AI:
                    String aiResponse = ragRetrievalService.getAiResponse(text, owner.getId());
                    if (aiResponse != null && !aiResponse.isBlank()) {
                        context.getMetadata().put("pendingResponse", aiResponse);
                        context.getMetadata().put("responseType", "AI");
                    } else {
                        context.getMetadata().put("responseType", "MENU");
                    }
                    break;
                case REUSE:
                    if (!lastOutgoing.isEmpty()) {
                        context.getMetadata().put("pendingResponse", lastOutgoing.get(0).getContent());
                        context.getMetadata().put("responseType", "AI");
                    }
                    break;
                case CLARIFY:
                case WARNING:
                    context.getMetadata().put("pendingResponse", guardrail.getSuggestion());
                    context.getMetadata().put("responseType", "PLAIN");
                    break;
                case IGNORE:
                    String fallbackMsg = "We couldn't process your request. Please select an option from the menu below.";
                    if ("abuse_throttled".equals(guardrail.getReason())) {
                        String msg = config.getGuardrailMessageAbuse();
                        if (msg != null && !msg.isBlank())
                            fallbackMsg = msg;
                    } else {
                        String msg = config.getGuardrailMessageGibberish();
                        if (msg != null && !msg.isBlank())
                            fallbackMsg = msg;
                    }
                    context.getMetadata().put("pendingResponse", fallbackMsg);
                    context.getMetadata().put("responseType", "MENU_OVERRIDE");
                    break;
                case MENU:
                    if ("gibberish".equals(guardrail.getReason())) {
                        String msg = config.getGuardrailMessageGibberish();
                        if (msg != null && !msg.isBlank()) {
                            context.getMetadata().put("pendingResponse", msg);
                            context.getMetadata().put("responseType", "MENU_OVERRIDE");
                            break;
                        }
                    }
                    context.getMetadata().put("responseType", "MENU");
                    break;
            }
            log.info("🤖 [AI-Stage] Decision for {}: {}", context.getMessageId(), guardrail.getDecision());
        } catch (Exception e) {
            log.error("❌ [AI-Stage] Failed for {}", context.getMessageId(), e);
            context.getMetadata().put("responseType", "MENU"); // Fallback
        }
    }
}
