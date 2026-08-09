package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.ThemeConfigDTO;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.NicheThemeService;
import com.chatcrmlite.backend.services.RagRetrievalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.services.ai.guardrail.GuardrailService;
import com.chatcrmlite.backend.dto.ai.GuardrailResult;
import com.chatcrmlite.backend.dto.ai.Decision;
import com.chatcrmlite.backend.services.WebChatService;
import com.chatcrmlite.backend.models.WebChatMessage;

@RestController
@RequestMapping("/api/v1/public")
public class PublicChatController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NicheThemeService themeService;

    @Autowired
    private RagRetrievalService ragRetrievalService;

    @Autowired
    private QuotaEnforcerService quotaEnforcerService;

    @Autowired
    private GuardrailService guardrailService;

    @Autowired
    private WebChatService webChatService;

    @Autowired
    private com.chatcrmlite.backend.services.livechat.LiveSupportService liveSupportService;

    @Autowired
    private com.chatcrmlite.backend.repositories.ContactRepository contactRepository;

    @PostMapping("/livechat/request/{businessId}")
    public ResponseEntity<Map<String, Object>> requestPublicHumanSupport(
            @PathVariable UUID businessId,
            @RequestBody Map<String, String> request) {

        String sessionId = request.getOrDefault("sessionId", "web_session_" + UUID.randomUUID().toString());
        String name = request.getOrDefault("name", "Website Visitor");
        String email = request.get("email");
        String phone = request.get("phone");

        User owner = userRepository.findById(businessId).orElse(null);
        if (owner == null) return ResponseEntity.notFound().build();

        String waId = "web:" + sessionId;
        com.chatcrmlite.backend.models.Contact contact = contactRepository.findByWaIdAndOwner(waId, owner)
                .orElseGet(() -> {
                    com.chatcrmlite.backend.models.Contact c = new com.chatcrmlite.backend.models.Contact();
                    c.setWaId(waId);
                    c.setName(name);
                    c.setEmail(email);
                    c.setOwner(owner);
                    c.setTenant(owner.getTenant());
                    c.setSource("WEBCHAT");
                    return contactRepository.save(c);
                });

        if (name != null && !name.isBlank()) contact.setName(name);
        if (email != null && !email.isBlank()) contact.setEmail(email);
        contactRepository.save(contact);

        com.chatcrmlite.backend.models.livechat.SupportState state = liveSupportService.requestHumanSupport(contact, UUID.randomUUID().toString());

        Map<String, Object> res = new java.util.HashMap<>();
        res.put("state", state.name());
        res.put("botPaused", contact.isBotPaused());

        if (state == com.chatcrmlite.backend.models.livechat.SupportState.ASSIGNED) {
            String agentName = contact.getAssignedAgent() != null ? contact.getAssignedAgent().getDisplayName() : "our agent";
            res.put("response", "🟢 You are now connected with support agent " + agentName + "! How can we assist you today?");
            res.put("assignedAgentName", agentName);
        } else {
            res.put("response", "⏳ All support agents are currently busy. You have been placed in queue. Expected wait time: 30 to 60 minutes. Please hold on!");
        }

        return ResponseEntity.ok(res);
    }

    @GetMapping("/config/{businessId}")
    public ResponseEntity<ThemeConfigDTO> getPublicConfig(@PathVariable UUID businessId) {
        return userRepository.findById(businessId)
                .map(user -> ResponseEntity.ok(themeService.getThemeForUser(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/chat/{businessId}")
    public ResponseEntity<Map<String, Object>> handlePublicChat(
            @PathVariable UUID businessId,
            @RequestBody Map<String, String> request) {
        
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required"));
        }

        String sessionId = request.getOrDefault("sessionId", "anonymous");
        User owner = userRepository.findById(businessId).orElse(null);
        
        if (owner != null) {
            webChatService.saveMessage(owner, sessionId, WebChatMessage.Sender.USER, message);
        }

        if (owner != null && owner.getTenant() != null) {
            try {
                SubscriptionPlan plan = quotaEnforcerService.getActiveSubscription(owner.getTenant().getId()).getPlan();
                if (!plan.isHasRagLlm()) {
                    String noRagMsg = "I am a menu-based assistant. Please use the options provided to interact.";
                    webChatService.saveMessage(owner, sessionId, WebChatMessage.Sender.BOT, noRagMsg);
                    return ResponseEntity.ok(Map.of("response", noRagMsg));
                }
            } catch (Exception e) {
                // Proceed normally if enforcement fails or tenant doesn't exist
            }
        }
        java.util.List<com.chatcrmlite.backend.dto.WidgetCtaDTO> ctaButtons = null;
        if (owner != null) {
            ThemeConfigDTO theme = themeService.getThemeForUser(owner);
            ctaButtons = theme.getCtaButtons();
            
            try {
                GuardrailResult guardrail = guardrailService.evaluate(message, "web_user_" + request.getOrDefault("sessionId", "anonymous"), false, owner.getBusinessSubType(), businessId);
                if (guardrail.getDecision() == Decision.GREETING) {
                    boolean isReturning = "true".equalsIgnoreCase(request.get("isReturning"));
                    String greeting = isReturning ? theme.getReturningMessage() : theme.getWelcomeMessage();
                    
                    if (greeting == null || greeting.isBlank()) {
                        String bizName = owner.getBusinessName() != null ? owner.getBusinessName() : "our business";
                        greeting = isReturning 
                            ? "👋 Welcome back to " + bizName + "! How can we help you today?"
                            : "👋 Hello! Welcome to " + bizName + ". How can we help you today?";
                    }
                    
                    Map<String, Object> responseMap = new java.util.HashMap<>();
                    responseMap.put("response", greeting);
                    if (ctaButtons != null) responseMap.put("ctaButtons", ctaButtons);
                    
                    webChatService.saveMessage(owner, sessionId, WebChatMessage.Sender.BOT, greeting);
                    return ResponseEntity.ok(responseMap);
                } else if (guardrail.getDecision() == Decision.IGNORE && "abuse_throttled".equals(guardrail.getReason())) {
                    Map<String, Object> responseMap = new java.util.HashMap<>();
                    responseMap.put("isGuardrail", true);
                    responseMap.put("reason", "abuse_throttled");
                    String fallbackMsg = theme.getGuardrailMessageAbuse();
                    if (fallbackMsg == null || fallbackMsg.isBlank()) {
                        fallbackMsg = "We cannot process requests containing inappropriate or abusive language. Please communicate respectfully or select an option from the menu.";
                    }
                    responseMap.put("response", fallbackMsg);
                    if (ctaButtons != null) responseMap.put("ctaButtons", ctaButtons);
                    webChatService.saveMessage(owner, sessionId, WebChatMessage.Sender.BOT, fallbackMsg);
                    return ResponseEntity.ok(responseMap);
                } else if (guardrail.getDecision() == Decision.MENU && "gibberish".equals(guardrail.getReason())) {
                    Map<String, Object> responseMap = new java.util.HashMap<>();
                    responseMap.put("isGuardrail", true);
                    responseMap.put("reason", "gibberish");
                    String fallbackMsg = theme.getGuardrailMessageGibberish();
                    if (fallbackMsg == null || fallbackMsg.isBlank()) {
                        fallbackMsg = "We couldn't understand your request. Please rephrase your message or select one of the available options below.";
                    }
                    responseMap.put("response", fallbackMsg);
                    if (ctaButtons != null) responseMap.put("ctaButtons", ctaButtons);
                    webChatService.saveMessage(owner, sessionId, WebChatMessage.Sender.BOT, fallbackMsg);
                    return ResponseEntity.ok(responseMap);
                } else if (guardrail.getDecision() == Decision.IGNORE && "spam_throttled".equals(guardrail.getReason())) {
                    Map<String, Object> responseMap = new java.util.HashMap<>();
                    responseMap.put("isGuardrail", true);
                    responseMap.put("reason", "gibberish");
                    String fallbackMsg = theme.getGuardrailMessageGibberish();
                    if (fallbackMsg == null || fallbackMsg.isBlank()) {
                        fallbackMsg = "We couldn't understand your request. Please rephrase your message or select one of the available options below.";
                    }
                    responseMap.put("response", fallbackMsg);
                    if (ctaButtons != null) responseMap.put("ctaButtons", ctaButtons);
                    webChatService.saveMessage(owner, sessionId, WebChatMessage.Sender.BOT, fallbackMsg);
                    return ResponseEntity.ok(responseMap);
                }
            } catch (Exception e) {
                // Ignore guardrail errors and fallback to RAG
            }
        }

        String response = ragRetrievalService.getAiResponse(message, businessId);
        
        if (response == null || response.isBlank()) {
            response = "I'm sorry, I don't have information about that. How else can I help you?";
        }

        Map<String, Object> responseMap = new java.util.HashMap<>();
        responseMap.put("response", response);
        if (ctaButtons != null) responseMap.put("ctaButtons", ctaButtons);

        if (owner != null) {
            webChatService.saveMessage(owner, sessionId, WebChatMessage.Sender.BOT, response);
        }

        return ResponseEntity.ok(responseMap);
    }
}
