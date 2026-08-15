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
            Contact contact = contactRepository.findByWaIdAndTenant_Id(context.getWaId(), context.getTenantId())
                    .orElseThrow(() -> new RuntimeException("Contact not found for tenant: " + context.getTenantId()));

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

            // Keyword check (Greeting/Menu/Cancel)
            String lower = text.trim().toLowerCase();
            boolean hasActiveFlow = Boolean.TRUE.equals(context.getMetadata().get("hasActiveFlow"));
            boolean isCancel = lower.matches("^(cancel|stop|exit|quit|terminate)$") || "cancel_flow".equals(selectionId);

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
                boolean isNavCommand = lower.matches("^(menu|options|help|start|services|show|cancel|exit|stop)$");

                if ("text".equals(type) && (isGreeting || isNavCommand)) {
                    flowStateMachine.resetFlow(contact);
                    context.getMetadata().put("responseType", isGreeting ? "GREETING" : "MENU");
                    return;
                }

                // Check Enterprise Native Flow Router before conversational bot
                boolean routedToFlow = tryRouteToNativeFlow(contact, owner, config, text, selectionId);
                if (routedToFlow) {
                    context.getMetadata().put("responseType", "FLOW_CONSUMED");
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

    private boolean tryRouteToNativeFlow(Contact contact, User owner, WhatsAppConfig config, String text, String selectionId) {
        String intentKey = detectIntent(text, selectionId);
        if (intentKey == null) {
            return false;
        }

        String routingJson = config.getFlowsRoutingConfigJson();
        if (routingJson != null && !routingJson.isBlank()) {
            try {
                JsonNode routingNode = objectMapper.readTree(routingJson);
                if (routingNode.has(intentKey)) {
                    JsonNode targetConfig = routingNode.get(intentKey);
                    boolean enabled = targetConfig.path("enabled").asBoolean(true);
                    String mode = targetConfig.path("mode").asText("CHATBOT");
                    String metaFlowId = targetConfig.path("metaFlowId").asText(null);

                    if (enabled) {
                        if ("NATIVE_FLOW".equalsIgnoreCase(mode) && metaFlowId != null && !metaFlowId.isBlank()) {
                            String ctaText = targetConfig.path("ctaText").asText("Open Form");
                            String promptText = targetConfig.path("promptText").asText("Please complete the form below:");
                            String headerText = targetConfig.path("headerText").asText(null);
                            String footerText = targetConfig.path("footerText").asText(config.getVerifiedName());

                            log.info("🎯 [FlowRouter] Dispatching Native Flow '{}' for intent '{}' to {}", metaFlowId, intentKey, contact.getWaId());
                            outboundService.sendFlow(contact, headerText, promptText, footerText, metaFlowId, ctaText, config, owner);
                            return true;
                        } else if ("CHATBOT".equalsIgnoreCase(mode)) {
                            log.info("🤖 [FlowRouter] Starting Chatbot flow for intent '{}' to {}", intentKey, contact.getWaId());
                            return flowStateMachine.startFlow(contact, owner, text, intentKey);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ [FlowRouter] Error evaluating flow routing config: {}", e.getMessage());
            }
        }

        // Fallback default: start conversational chatbot flow for this intent
        log.info("🤖 [FlowRouter] Starting default Chatbot flow for intent '{}' to {}", intentKey, contact.getWaId());
        return flowStateMachine.startFlow(contact, owner, text, intentKey);
    }

    private String detectIntent(String text, String selectionId) {
        String combined = ((text != null ? text : "") + " " + (selectionId != null ? selectionId : "")).toLowerCase();
        
        // Appointment keywords & triggers
        if (combined.matches(".*(appointment|doctor|clinic|consultation|checkup|specialist|dentist|physician|trigger_flow_appointment).*")) {
            return "appointments";
        }
        // Booking keywords & triggers
        if (combined.matches(".*(salon|spa|booking|reserve|slot|table|haircut|facial|massage|reservation|trigger_flow_booking).*")) {
            return "bookings";
        }
        // Lead / Enquiry keywords & triggers
        if (combined.matches(".*(quote|pricing|inquiry|inquire|lead|enquiry|enquire|estimate|catalog|trigger_flow_lead|trigger_flow|trigger_0).*")) {
            return "leadGen";
        }
        // Feedback & Support keywords & triggers
        if (combined.matches(".*(feedback|rating|review|survey|complaint|support|trigger_flow_support).*")) {
            return "feedback";
        }
        return null;
    }
}
