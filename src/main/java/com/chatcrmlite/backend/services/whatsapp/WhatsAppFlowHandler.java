package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.flow.FlowStateMachine;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppFlowHandler {

    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final ContactRepository contactRepository;
    private final FlowStateMachine flowStateMachine;
    private final ObjectMapper objectMapper;
    private final WhatsAppOutboundService outboundService;

    @Transactional
    public void executeFlowLogic(ProcessingContext context) {
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
            String type = (String) context.getMetadata().get("type");
            
            JsonNode root = objectMapper.readTree(context.getPayload());
            JsonNode value = root.path("entry").get(0).path("changes").get(0).path("value");
            JsonNode messageNode = value.path("messages").get(0);
            
            String selectionId = null;
            boolean isInteractiveSelection = false;
            if ("interactive".equals(type)) {
                JsonNode interactive = messageNode.path("interactive");
                selectionId = interactive.path(interactive.path("type").asText()).path("id").asText();
                isInteractiveSelection = true;
            }

            // Keyword check (Greeting/Menu)
            String lower = text.trim().toLowerCase();
            boolean hasActiveFlow = Boolean.TRUE.equals(context.getMetadata().get("hasActiveFlow"));
            boolean isCancel = lower.equals("cancel");

            if (hasActiveFlow) {
                if (isCancel) {
                    flowStateMachine.resetFlow(contact);
                    context.getMetadata().put("responseType", "NONE");
                    log.info("🛑 User cancelled the active flow.");
                    
                    if (config.getFlowCancelMenuJson() != null && !config.getFlowCancelMenuJson().isBlank()) {
                        try {
                            com.chatcrmlite.backend.dto.MenuDto menu = objectMapper.readValue(
                                    config.getFlowCancelMenuJson(), com.chatcrmlite.backend.dto.MenuDto.class);
                            outboundService.sendInteractiveMenu(contact, menu, "🛑 Your form has been terminated.", config, owner);
                        } catch (Exception e) {
                            log.warn("Failed to parse configured cancel menu, falling back to text", e);
                            outboundService.sendText(contact, "🛑 Your form has been terminated.", config, owner);
                        }
                    } else {
                        outboundService.sendText(contact, "🛑 Your form has been terminated.", config, owner);
                    }
                    return;
                }
                // Do not intercept other keywords if a flow is active. Let the flow validate the input.
            } else {
                boolean isGreeting = lower.matches("^(hi|hello|hey|namaste|hi there|hello there)$");
                boolean isNavCommand = lower.matches("^(menu|options|help|start|services|show)$");

                if ("text".equals(type) && (isGreeting || isNavCommand)) {
                    flowStateMachine.resetFlow(contact);
                    context.getMetadata().put("responseType", isGreeting ? "GREETING" : "MENU");
                    return;
                }
            }

            // Flow Engine execution
            boolean consumed = flowStateMachine.processFlow(contact, owner, text, selectionId, isInteractiveSelection);
            if (consumed) {
                context.getMetadata().put("responseType", "FLOW_CONSUMED");
                return;
            }

            // If it was an interactive selection, let's put it as INTERACTIVE_SELECTION instead of MENU
            if (isInteractiveSelection && selectionId != null) {
                context.getMetadata().put("responseType", "INTERACTIVE_SELECTION");
                context.getMetadata().put("selectionId", selectionId);
                log.info("🔀 [Flow-Stage] Handled interactive selection '{}' for {}", selectionId, context.getMessageId());
                return;
            }

            // Fallback if not consumed
            if (!context.getMetadata().containsKey("responseType")) {
                context.getMetadata().put("responseType", "MENU");
            }
            
            log.info("🔀 [Flow-Stage] Completed for {}", context.getMessageId());
        } catch (Exception e) {
            log.error("❌ [Flow-Stage] Failed for {}", context.getMessageId(), e);
        }
    }
}
