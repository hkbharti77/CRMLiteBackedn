package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.ThemeConfigDTO;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.NicheThemeService;
import com.chatcrmlite.backend.services.RagRetrievalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import com.chatcrmlite.backend.config.RateLimitConfig;

import java.util.Map;
import java.util.UUID;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.services.ai.guardrail.GuardrailService;
import com.chatcrmlite.backend.dto.ai.GuardrailResult;
import com.chatcrmlite.backend.dto.ai.Decision;
import com.chatcrmlite.backend.services.WebChatService;
import com.chatcrmlite.backend.models.WebChatMessage;
import com.chatcrmlite.backend.services.memory.ConversationMemoryService;
import com.chatcrmlite.backend.dto.memory.ConversationContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;

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

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private RateLimitConfig rateLimitConfig;

    @Autowired(required = false)
    private com.chatcrmlite.backend.services.whatsapp.catalog.CatalogCandidateService catalogCandidateService;

    @Autowired(required = false)
    private com.chatcrmlite.backend.services.whatsapp.catalog.AiCatalogDecisionService aiCatalogDecisionService;

    @Autowired(required = false)
    private com.chatcrmlite.backend.repositories.TenantAiCatalogRepository tenantAiCatalogRepository;

    @Autowired(required = false)
    private com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsAppConfigRepository;

    @Autowired(required = false)
    private com.chatcrmlite.backend.services.billing.CreditMeteringService creditMeteringService;

    @Autowired(required = false)
    private com.chatcrmlite.backend.services.chat.ChatPersistenceProducer chatPersistenceProducer;

    private final Cache<UUID, User> userCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    private User getCachedUser(UUID businessId) {
        if (businessId == null) return null;
        return userCache.get(businessId, id ->
                userRepository.findByIdWithTenant(id).orElse(null)
        );
    }

    public void invalidateUserCache(UUID businessId) {
        if (businessId != null) {
            userCache.invalidate(businessId);
        }
    }

    private void persistChatMessage(User owner, String sessionId, WebChatMessage.Sender sender, String content, String source) {
        if (owner == null) return;
        if (chatPersistenceProducer != null) {
            chatPersistenceProducer.enqueueMessage(owner.getId(), sessionId, sender, content, source, null);
        } else {
            webChatService.saveMessage(owner, sessionId, sender, content);
        }
    }

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
        User user = getCachedUser(businessId);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(themeService.getThemeForUser(user));
    }

    /**
     * DPDP Right to Erasure: Erases user chat session and transcript from backend DB.
     */
    @PostMapping("/chat/{businessId}/erasure")
    public ResponseEntity<Map<String, Object>> handleDataErasure(
            @PathVariable UUID businessId,
            @RequestBody Map<String, String> request) {
        User owner = userRepository.findById(businessId).orElse(null);
        if (owner == null) {
            return ResponseEntity.notFound().build();
        }
        String sessionId = request != null ? request.get("sessionId") : null;
        if (sessionId != null && !sessionId.isBlank()) {
            webChatService.eraseSessionData(owner, sessionId);
        }
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "User session and conversation transcripts erased successfully under DPDP Right to Erasure."
        ));
    }

    @PostMapping("/chat/{businessId}")
    public ResponseEntity<Map<String, Object>> handlePublicChat(
            @PathVariable UUID businessId,
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {
        
        String clientIp = httpRequest.getRemoteAddr();
        
        // 1. Consume Global IP bucket
        if (!rateLimitConfig.tryConsume("global:" + clientIp, RateLimitConfig.Tier.GLOBAL_AI)) {
            org.slf4j.LoggerFactory.getLogger(PublicChatController.class)
                .warn("[PublicChat] GLOBAL_AI Rate limit exceeded for IP={}", clientIp);
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(Map.of(
                        "error", "Too many requests. Please wait a moment before sending another message.",
                        "retryAfterSeconds", 60
                    ));
        }

        // 2. Consume Business IP bucket
        String businessKey = clientIp + ":" + businessId;
        if (!rateLimitConfig.tryConsume(businessKey, RateLimitConfig.Tier.AI)) {
            org.slf4j.LoggerFactory.getLogger(PublicChatController.class)
                .warn("[PublicChat] AI Rate limit exceeded for IP={} businessId={}", clientIp, businessId);
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(Map.of(
                        "error", "Too many requests. Please wait a moment before sending another message.",
                        "retryAfterSeconds", 60
                    ));
        }
        
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required"));
        }

        String sessionId = request.getOrDefault("sessionId", "anonymous");
        User owner = getCachedUser(businessId);
        
        if (owner != null) {
            persistChatMessage(owner, sessionId, WebChatMessage.Sender.USER, message, "WEBCHAT");
        }

        if (owner != null && owner.getTenant() != null) {
            try {
                SubscriptionPlan plan = quotaEnforcerService.getActiveSubscription(owner.getTenant().getId()).getPlan();
                boolean hasCredits = creditMeteringService != null && creditMeteringService.hasAvailableCredits(owner.getTenant().getId(), 1);

                // Allow AI if plan has RAG LLM OR tenant has AI credits remaining
                if (!plan.isHasRagLlm() && !hasCredits) {
                    String noRagMsg = "I am a menu-based assistant. Please use the options provided to interact.";
                    persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, noRagMsg, "MENU");
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
                    
                    persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, greeting, "GREETING");
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
                    persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, fallbackMsg, "GUARDRAIL");
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
                    persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, fallbackMsg, "GUARDRAIL");
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
                    persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, fallbackMsg, "GUARDRAIL");
                    return ResponseEntity.ok(responseMap);
                }
            } catch (Exception e) {
                // Ignore guardrail errors and fallback to RAG
            }
        }

        // Check if tenant has AI Catalogs enabled and matches query
        if (owner != null && owner.getTenant() != null && whatsAppConfigRepository != null && catalogCandidateService != null && aiCatalogDecisionService != null && tenantAiCatalogRepository != null) {
            try {
                var waConfig = whatsAppConfigRepository.findByTenantId(owner.getTenant().getId()).orElse(null);
                if (waConfig != null && Boolean.TRUE.equals(waConfig.getEnableAiCatalogs())) {
                    double threshold = (waConfig.getCatalogRelevanceThreshold() != null) ? waConfig.getCatalogRelevanceThreshold() : 0.65;
                    var candidates = catalogCandidateService.findCandidates(owner.getTenant().getId(), message, threshold);
                    var catalogAction = aiCatalogDecisionService.decideFromCandidates(candidates, message, com.chatcrmlite.backend.dto.ai.action.AiAction.DecisionSource.NATIVE_TOOL);

                    if (catalogAction.type() == com.chatcrmlite.backend.dto.ai.action.AiAction.AiActionType.CLARIFY) {
                        Map<String, Object> responseMap = new java.util.HashMap<>();
                        responseMap.put("response", catalogAction.caption());
                        if (ctaButtons != null) responseMap.put("ctaButtons", ctaButtons);
                        persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, catalogAction.caption(), "CATALOG");
                        return ResponseEntity.ok(responseMap);
                    } else if (catalogAction.type() == com.chatcrmlite.backend.dto.ai.action.AiAction.AiActionType.SEND_CATALOG && catalogAction.catalogId() != null) {
                        var catalogOpt = tenantAiCatalogRepository.findByIdAndTenantId(catalogAction.catalogId(), owner.getTenant().getId());
                        if (catalogOpt.isPresent() && catalogOpt.get().getStatus() == com.chatcrmlite.backend.models.CatalogStatus.ACTIVE) {
                            var cat = catalogOpt.get();
                            String botMsg = "Here is our " + cat.getTitle() + " you requested:";
                            Map<String, Object> responseMap = new java.util.HashMap<>();
                            responseMap.put("response", botMsg);

                            Map<String, Object> catalogMeta = new java.util.HashMap<>();
                            catalogMeta.put("id", cat.getId().toString());
                            catalogMeta.put("title", cat.getTitle());
                            catalogMeta.put("description", cat.getDescription());
                            catalogMeta.put("url", "/api/v1/public/catalogs/" + cat.getId() + "/file");
                            catalogMeta.put("downloadUrl", "/api/v1/public/catalogs/" + cat.getId() + "/file?download=true");
                            catalogMeta.put("directCloudinaryUrl", cat.getCloudinaryUrl());
                            catalogMeta.put("fileName", cat.getFileName());
                            catalogMeta.put("mediaType", cat.getMediaType());
                            catalogMeta.put("fileSizeBytes", cat.getFileSizeBytes());
                            responseMap.put("catalog", catalogMeta);

                            if (ctaButtons != null) responseMap.put("ctaButtons", ctaButtons);
                            persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, botMsg + " [Document: " + cat.getFileName() + "]", "CATALOG");
                            return ResponseEntity.ok(responseMap);
                        }
                    }
                }
            } catch (Exception catEx) {
                // Ignore catalog errors and proceed with normal RAG
            }
        }

        ConversationContext memContext = conversationMemoryService.getWebChatContext(owner, sessionId, message);
        String response = ragRetrievalService.getAiResponse(memContext, businessId);
        
        if (response == null || response.isBlank()) {
            response = "I'm sorry, I don't have information about that. How else can I help you?";
        }

        Map<String, Object> responseMap = new java.util.HashMap<>();
        responseMap.put("response", response);
        if (ctaButtons != null) responseMap.put("ctaButtons", ctaButtons);

        // Deduct 1 AI credit if answering via credit metering
        if (owner != null && owner.getTenant() != null && creditMeteringService != null) {
            try {
                if (creditMeteringService.hasAvailableCredits(owner.getTenant().getId(), 1)) {
                    creditMeteringService.deductCredits(owner.getTenant().getId(), 1, "PUBLIC_CHAT", "Chatbot AI Query", sessionId);
                }
            } catch (Exception credEx) {
                // Don't block response on metering error
            }
        }

        if (owner != null) {
            persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, response, "RAG");
        }

        return ResponseEntity.ok(responseMap);
    }

    /**
     * Phase 2: High-Performance Streaming Chat Endpoint (Server-Sent Events)
     * Provides sub-500ms TTFT, atomic credit reservation, and non-blocking durable message persistence.
     */
    @PostMapping(value = "/chat/{businessId}/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handlePublicChatStream(
            @PathVariable UUID businessId,
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        SseEmitter emitter = new SseEmitter(90_000L); // 90 second timeout

        String clientIp = httpRequest.getRemoteAddr();

        // 1. Rate limiting
        if (!rateLimitConfig.tryConsume("global:" + clientIp, RateLimitConfig.Tier.GLOBAL_AI) ||
            !rateLimitConfig.tryConsume(clientIp + ":" + businessId, RateLimitConfig.Tier.AI)) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                        "code", "RATE_LIMIT_EXCEEDED",
                        "message", "Too many requests. Please wait a moment before sending another message."
                )));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        String message = request.get("message");
        if (message == null || message.isBlank()) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("code", "BAD_REQUEST", "message", "Message is required")));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        String sessionId = request.getOrDefault("sessionId", "anonymous");
        User owner = getCachedUser(businessId);
        if (owner == null) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("code", "NOT_FOUND", "message", "Business not found")));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        // Asynchronously enqueue visitor message to Redis Stream
        persistChatMessage(owner, sessionId, WebChatMessage.Sender.USER, message, "WEBCHAT");

        ThemeConfigDTO theme = themeService.getThemeForUser(owner);
        java.util.List<com.chatcrmlite.backend.dto.WidgetCtaDTO> ctaButtons = theme != null ? theme.getCtaButtons() : null;

        // Verify Subscription / Credits
        if (owner.getTenant() != null) {
            try {
                SubscriptionPlan plan = quotaEnforcerService.getActiveSubscription(owner.getTenant().getId()).getPlan();
                boolean hasCredits = creditMeteringService != null && creditMeteringService.hasAvailableCredits(owner.getTenant().getId(), 1);
                if (!plan.isHasRagLlm() && !hasCredits) {
                    String noRagMsg = "I am a menu-based assistant. Please use the options provided to interact.";
                    persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, noRagMsg, "MENU");
                    sendSingleChunkAndDone(emitter, noRagMsg, "MENU", ctaButtons);
                    return emitter;
                }
            } catch (Exception ignored) {}
        }

        // Guardrail evaluation
        try {
            GuardrailResult guardrail = guardrailService.evaluate(message, "web_user_" + sessionId, false, owner.getBusinessSubType(), businessId);
            if (guardrail.getDecision() == Decision.GREETING) {
                boolean isReturning = "true".equalsIgnoreCase(request.get("isReturning"));
                String greeting = isReturning ? theme.getReturningMessage() : theme.getWelcomeMessage();
                if (greeting == null || greeting.isBlank()) {
                    String bizName = owner.getBusinessName() != null ? owner.getBusinessName() : "our business";
                    greeting = isReturning 
                        ? "👋 Welcome back to " + bizName + "! How can we help you today?"
                        : "👋 Hello! Welcome to " + bizName + ". How can we help you today?";
                }
                persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, greeting, "GREETING");
                sendSingleChunkAndDone(emitter, greeting, "GREETING", ctaButtons);
                return emitter;
            } else if (guardrail.getDecision() == Decision.IGNORE || guardrail.getDecision() == Decision.MENU) {
                String fallbackMsg = theme.getGuardrailMessageGibberish();
                if (fallbackMsg == null || fallbackMsg.isBlank()) {
                    fallbackMsg = "We couldn't understand your request. Please rephrase your message or select an option below.";
                }
                persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, fallbackMsg, "GUARDRAIL");
                sendSingleChunkAndDone(emitter, fallbackMsg, "GUARDRAIL", ctaButtons);
                return emitter;
            }
        } catch (Exception ignored) {}

        // Credit reservation (Two-Phase Protocol)
        final java.util.concurrent.atomic.AtomicReference<UUID> reservationHolder = new java.util.concurrent.atomic.AtomicReference<>();
        if (owner.getTenant() != null && creditMeteringService != null) {
            try {
                UUID resId = creditMeteringService.reserveCredits(owner.getTenant().getId(), 1, java.time.Duration.ofMinutes(5), sessionId);
                reservationHolder.set(resId);
            } catch (Exception credEx) {
                org.slf4j.LoggerFactory.getLogger(PublicChatController.class)
                        .warn("⚠️ Credit reservation failed for tenant {}: {}", owner.getTenant().getId(), credEx.getMessage());
            }
        }

        // Disconnect & Timeout lifecycle handlers
        emitter.onTimeout(() -> {
            org.slf4j.LoggerFactory.getLogger(PublicChatController.class)
                    .warn("⏱️ [PublicChatStream] SSE Stream timed out for sessionId={}", sessionId);
            if (reservationHolder.get() != null && creditMeteringService != null) {
                creditMeteringService.releaseReservation(reservationHolder.get());
            }
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("code", "STREAM_TIMEOUT", "message", "Request timed out.")));
                emitter.complete();
            } catch (Exception ignored) {}
        });

        emitter.onError(err -> {
            org.slf4j.LoggerFactory.getLogger(PublicChatController.class)
                    .warn("🔌 [PublicChatStream] SSE Client disconnected or error for sessionId={}: {}", sessionId, err.getMessage());
            if (reservationHolder.get() != null && creditMeteringService != null) {
                creditMeteringService.releaseReservation(reservationHolder.get());
            }
        });

        ConversationContext memContext = conversationMemoryService.getWebChatContext(owner, sessionId, message);

        // Execute streaming RAG pipeline
        ragRetrievalService.streamAiResponse(
                memContext,
                businessId,
                token -> {
                    try {
                        emitter.send(SseEmitter.event().name("chunk").data(Map.of("text", token)));
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(PublicChatController.class)
                                .debug("Failed sending chunk to SSE emitter: {}", e.getMessage());
                    }
                },
                meta -> {
                    try {
                        Map<String, Object> doneData = new java.util.HashMap<>();
                        doneData.put("done", true);
                        doneData.put("source", meta.source());
                        if (ctaButtons != null) doneData.put("ctaButtons", ctaButtons);
                        emitter.send(SseEmitter.event().name("done").data(doneData));
                        emitter.complete();

                        // Settle credit reservation
                        if (reservationHolder.get() != null && creditMeteringService != null) {
                            creditMeteringService.settleReservation(reservationHolder.get(), 1, "Public Web Chat Stream");
                        }

                        // Asynchronously persist bot response to Redis Stream
                        persistChatMessage(owner, sessionId, WebChatMessage.Sender.BOT, meta.fullResponse(), meta.source());
                    } catch (Exception ex) {
                        org.slf4j.LoggerFactory.getLogger(PublicChatController.class)
                                .warn("Error finalizing SSE stream: {}", ex.getMessage());
                    }
                },
                error -> {
                    try {
                        if (reservationHolder.get() != null && creditMeteringService != null) {
                            creditMeteringService.releaseReservation(reservationHolder.get());
                        }
                        emitter.send(SseEmitter.event().name("error").data(Map.of(
                                "code", "GENERATION_ERROR",
                                "message", "Unable to generate response. Please try again."
                        )));
                        emitter.complete();
                    } catch (Exception ignored) {}
                }
        );

        return emitter;
    }

    private void sendSingleChunkAndDone(SseEmitter emitter, String content, String source, java.util.List<com.chatcrmlite.backend.dto.WidgetCtaDTO> ctaButtons) {
        try {
            emitter.send(SseEmitter.event().name("chunk").data(Map.of("text", content)));
            Map<String, Object> doneData = new java.util.HashMap<>();
            doneData.put("done", true);
            doneData.put("source", source);
            if (ctaButtons != null) doneData.put("ctaButtons", ctaButtons);
            emitter.send(SseEmitter.event().name("done").data(doneData));
            emitter.complete();
        } catch (Exception ignored) {}
    }
}
