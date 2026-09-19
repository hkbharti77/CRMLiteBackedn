package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.RagRetrievalService;
import com.chatcrmlite.backend.services.ai.guardrail.GuardrailService;
import com.chatcrmlite.backend.services.memory.ConversationMemoryService;
import com.chatcrmlite.backend.dto.memory.ConversationContext;
import com.chatcrmlite.backend.dto.ai.Decision;
import com.chatcrmlite.backend.dto.ai.GuardrailResult;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WhatsAppAiService {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppAiService.class);

    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final ContactRepository contactRepository;
    private final MessageRepository messageRepository;
    private final GuardrailService guardrailService;
    private final RagRetrievalService ragRetrievalService;
    private final ConversationMemoryService conversationMemoryService;
    @Autowired(required = false) private UserRepository userRepository;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.catalog.CatalogCandidateService catalogCandidateService;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.catalog.AiCatalogDecisionService aiCatalogDecisionService;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.catalog.AiCatalogActionValidator aiCatalogActionValidator;
    @Autowired(required = false) private WhatsAppOutboundService whatsAppOutboundService;

    @Transactional
    public void evaluateAiIntake(ProcessingContext context) {
        log.info("[WhatsApp-Bot] AI processing started correlationId={} messageId={} tenantId={}",
                context.getMessageId(), context.getMessageId(), context.getTenantId());

        try {
            WhatsAppConfig config = whatsappConfigRepository.findByTenantId(context.getTenantId())
                    .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant: " + context.getTenantId()));

            User owner = config.getUser();
            if (owner == null && userRepository != null && config.getTenant() != null) {
                owner = userRepository.findByTenantIdAndRole(context.getTenantId(), User.Role.OWNER)
                        .stream().findFirst()
                        .orElseGet(() -> userRepository.findAllByTenant(config.getTenant()).stream().findFirst().orElse(null));
            }

            Contact contact = contactRepository.findByWaIdAndTenant_Id(context.getWaId(), context.getTenantId())
                    .orElseThrow(() -> new IllegalStateException("Contact not found for tenant: " + context.getTenantId()));

            String text = (String) context.getMetadata().get("text");
            if (text == null) {
                text = "";
            }

            UUID ownerId = owner != null ? owner.getId() : context.getTenantId();
            String businessSubType = owner != null ? owner.getBusinessSubType() : "GENERAL";

            // Check if last response was AI for context boost
            PageRequest pageRequest = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "timestamp"));
            List<Message> lastOutgoing = messageRepository.findByContactAndDirection(contact,
                    Message.Direction.OUTGOING, pageRequest);
            boolean lastWasAi = lastOutgoing != null && !lastOutgoing.isEmpty()
                    && lastOutgoing.get(0) != null
                    && lastOutgoing.get(0).getContent() != null
                    && !lastOutgoing.get(0).getContent().contains("[Sent Interactive Menu]");

            GuardrailResult guardrail = guardrailService.evaluate(text, contact.getWaId(), lastWasAi,
                    businessSubType, ownerId);

            Decision decision = (guardrail != null && guardrail.getDecision() != null)
                    ? guardrail.getDecision()
                    : Decision.CALL_AI;

            log.info("[WhatsApp-Bot] RAG retrieval completed correlationId={} decision={}",
                    context.getMessageId(), decision);

            switch (decision) {
                case GREETING:
                    context.getMetadata().put("responseType", "GREETING");
                    break;
                case CALL_AI:
                    if (Boolean.TRUE.equals(config.getEnableAiCatalogs()) && catalogCandidateService != null && aiCatalogDecisionService != null && aiCatalogActionValidator != null) {
                        double threshold = config.getCatalogRelevanceThreshold() != null ? config.getCatalogRelevanceThreshold() : 0.65;
                        var candidates = catalogCandidateService.findCandidates(context.getTenantId(), text, threshold);
                        var catalogAction = aiCatalogDecisionService.decideFromCandidates(candidates, text, com.chatcrmlite.backend.dto.ai.action.AiAction.DecisionSource.NATIVE_TOOL);

                        if (catalogAction.type() == com.chatcrmlite.backend.dto.ai.action.AiAction.AiActionType.CLARIFY) {
                            context.getMetadata().put("pendingResponse", catalogAction.caption());
                            context.getMetadata().put("responseType", "PLAIN");
                            aiCatalogActionValidator.recordAudit(context.getTenantId(), contact, catalogAction, context.getMessageId(), "CLARIFY", true, false, "Clarification asked", null);
                            break;
                        } else if (catalogAction.type() == com.chatcrmlite.backend.dto.ai.action.AiAction.AiActionType.SEND_CATALOG) {
                            var valResult = aiCatalogActionValidator.validate(context.getTenantId(), contact, catalogAction, config, context.getMessageId());
                            if (valResult.approved() && whatsAppOutboundService != null) {
                                String mediaUrl = whatsAppOutboundService.resolveCatalogDocumentUrl(valResult.catalog());
                                String captionText = (catalogAction.caption() != null && !catalogAction.caption().isBlank())
                                        ? catalogAction.caption()
                                        : "Here is the " + valResult.catalog().getTitle() + " you requested!";

                                boolean isImage = "IMAGE".equalsIgnoreCase(valResult.catalog().getMediaType())
                                        || (valResult.catalog().getMimeType() != null && valResult.catalog().getMimeType().startsWith("image/"))
                                        || (valResult.catalog().getFileName() != null && valResult.catalog().getFileName().matches("(?i).*\\.(png|jpe?g|webp|gif)$"));

                                context.getMetadata().put("pendingResponse", captionText);
                                if (isImage) {
                                    context.getMetadata().put("imgUrl", mediaUrl);
                                } else {
                                    context.getMetadata().put("documentUrl", mediaUrl);
                                    context.getMetadata().put("documentFilename", valResult.catalog().getFileName());
                                }
                                context.getMetadata().put("responseType", "PLAIN");
                                aiCatalogActionValidator.markExecuted(context.getTenantId(), contact, catalogAction, valResult.catalog(), context.getMessageId(), null, valResult.idempotencyKey());
                                break;
                            }
                        }
                    }

                    ConversationContext memContext = conversationMemoryService.getWhatsAppContext(contact, text);
                    String aiResponse = ragRetrievalService.getAiResponse(memContext, ownerId);
                    if (aiResponse != null && !aiResponse.isBlank()) {
                        context.getMetadata().put("pendingResponse", aiResponse);
                        context.getMetadata().put("responseType", "AI");
                    } else {
                        context.getMetadata().put("responseType", "MENU");
                    }
                    break;
                case REUSE:
                    if (lastOutgoing != null && !lastOutgoing.isEmpty() && lastOutgoing.get(0) != null) {
                        context.getMetadata().put("pendingResponse", lastOutgoing.get(0).getContent());
                        context.getMetadata().put("responseType", "AI");
                    }
                    break;
                case CLARIFY:
                case WARNING:
                    context.getMetadata().put("pendingResponse", guardrail != null ? guardrail.getSuggestion() : null);
                    context.getMetadata().put("responseType", "PLAIN");
                    break;
                case IGNORE:
                    String fallbackMsg = "We couldn't process your request. Please select an option from the menu below.";
                    if (guardrail != null && "abuse_throttled".equals(guardrail.getReason())) {
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
                    if (guardrail != null && "gibberish".equals(guardrail.getReason())) {
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
            log.info("[WhatsApp-Bot] Response generated correlationId={} decision={} responseType={}",
                    context.getMessageId(), decision, context.getMetadata().get("responseType"));
        } catch (Exception e) {
            log.error("[WhatsApp-Bot] Fallback to MENU due to error for messageId={}: {}",
                    context.getMessageId(), e.getMessage(), e);
            context.getMetadata().put("responseType", "MENU"); // Safe fallback to menu
        }
    }
}
