package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.dto.MenuDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.util.concurrent.atomic.AtomicInteger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
public class WhatsAppService {

    @Autowired private ContactRepository contactRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private LeadService leadService;
    @Autowired private MessageRepository messageRepository;
    @Autowired private WhatsAppConfigRepository whatsappConfigRepository;
    @Autowired private WhatsAppClient whatsappClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private com.chatcrmlite.backend.repositories.BusinessServiceRepository businessServiceRepository;
    @Autowired private WhatsAppConfigRepository configRepository;

    public static final String SOS_LABEL = "\uD83C\uDD98 Human Support";
    public static final String TRUST_LABEL = "\u2B50 Trust & Reviews";
    public static final String OFFER_LABEL = "\uD83C\uDF81 Special Offer";
    public static final String ABOUT_LABEL = "\uD83D\uDCC2 About & Contact";

    /** Injected lazily to avoid circular dependency */
    @Autowired private WhatsAppFlowService flowService;
    
    @Autowired private RagRetrievalService ragRetrievalService;
    @Autowired private RagGuardrailService guardrailService;
    @Autowired private FlowTemplateEngine templateEngine;

    @Value("${app.public.url:}")
    private String publicAppUrl;

    // â”€â”€ Global Circuit Breaker for Guardrail â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private final AtomicInteger globalGuardrailFailures = new AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong lastCircuitReset = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    // â”€â”€ Simple Rate Limiter (per phone number) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private final Map<String, io.github.bucket4j.Bucket> buckets = new java.util.concurrent.ConcurrentHashMap<>();

    private io.github.bucket4j.Bucket createNewBucket() {
        return io.github.bucket4j.Bucket.builder()
                .addLimit(io.github.bucket4j.Bandwidth.classic(10, io.github.bucket4j.Refill.greedy(10, java.time.Duration.ofMinutes(1))))
                .build();
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //  Webhook entry point
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Transactional
    public void processWebhook(String payload) {
        log.info("\uD83D\uDCE5 [Webhook-Raw] Incoming Payload: {}", payload);
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode entries = root.path("entry");
            for (JsonNode entry : entries) {
                for (JsonNode change : entry.path("changes")) {
                    JsonNode value = change.path("value");
                    JsonNode messages = value.path("messages");
                    if (messages.isArray()) {
                        String phoneNumberId = value.path("metadata").path("phone_number_id").asText();
                        // contacts[] carries the WhatsApp profile name
                        JsonNode contactsNode = value.path("contacts");
                        for (JsonNode messageNode : messages) {
                            handleIncomingMessage(messageNode, phoneNumberId, contactsNode);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[Webhook] Processing failed", e);
            throw new RuntimeException("Error processing WhatsApp webhook", e);
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //  Core incoming message handler
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private void handleIncomingMessage(JsonNode messageNode, String phoneNumberId, JsonNode contactsNode) {
        String from       = messageNode.path("from").asText();
        String waMessageId= messageNode.path("id").asText();
        long   timestamp  = messageNode.path("timestamp").asLong();
        String type       = messageNode.path("type").asText();

        // â”€â”€ 1. Parse incoming text / interactive selection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        String text = "Media / Unsupported Message";
        String selectionId = null;
        boolean isInteractiveSelection = false;

        if ("text".equals(type)) {
            text = messageNode.path("text").path("body").asText();
        } else if ("interactive".equals(type)) {
            JsonNode interactive = messageNode.path("interactive");
            String interactiveType = interactive.path("type").asText();
            if ("list_reply".equals(interactiveType)) {
                text = interactive.path("list_reply").path("title").asText();
                selectionId = interactive.path("list_reply").path("id").asText();
                isInteractiveSelection = true;
            } else if ("button_reply".equals(interactiveType)) {
                text = interactive.path("button_reply").path("title").asText();
                selectionId = interactive.path("button_reply").path("id").asText();
                isInteractiveSelection = true;
            }
        }

        // â”€â”€ 2. Resolve tenant config â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        var configOpt = whatsappConfigRepository.findByPhoneNumberId(phoneNumberId.trim());
        if (configOpt.isEmpty()) {
            log.warn("[Webhook] No tenant config for phoneNumberId={}", phoneNumberId);
            return;
        }
        WhatsAppConfig config = configOpt.get();
        User owner = config.getUser();

        // â”€â”€ 3. Mark incoming message as READ (blue ticks âœ“âœ“) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Fire-and-forget: non-fatal if Meta rejects it (e.g. token expiry)
        try {
            whatsappClient.markAsRead(waMessageId, config.getAccessToken(), config.getPhoneNumberId());
        } catch (Exception e) {
            log.warn("[MarkRead] Could not mark message as read: {}", e.getMessage());
        }

        // â”€â”€ 4. Resolve (or create) contact â€” auto-fetch real WA profile name â”€
        String profileName = extractProfileName(contactsNode, from);
        Contact contact = resolveContact(from, profileName, owner);

        // â”€â”€ 5. Resolve (or create) lead â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Determine enquiry type based on message context
        String enquiryType = determineEnquiryType(text, type, isInteractiveSelection, contact);
        boolean isNewLead = resolveAndMaybeSaveLead(contact, owner, enquiryType);

        // â”€â”€ 6. Keyword Prioritization (Reset Hatch) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // We check for "hi/hello/menu" keywords BEFORE regular flows so users
        // can always escape a stuck conversation.
        String lower = text.trim().toLowerCase();
        boolean isGreeting = lower.matches("^(hi|hello|hey|namaste|hi there|hello there)$");
        boolean isNavCommand = lower.matches("^(menu|options|help|start|services|show)$");

        if ("text".equals(type) && (isGreeting || isNavCommand)) {
            log.info("[Reset] Keyword '{}' received from {}. Resetting flow.", lower, contact.getWaId());
            flowService.resetFlow(contact);

            if (isGreeting) {
                sendGreetingWithMenu(contact, config, owner, false);
            } else {
                sendTenantMenuToContact(contact, config);
            }

            saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
            return;
        }

        // â”€â”€ 7. If new lead â†’ send greeting + menu â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (isNewLead && "text".equals(type)) {
            sendGreetingWithMenu(contact, config, owner, true);
            saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
            return;
        }

        // â”€â”€ 8. Route via Flow Engine (Multi-step Logic) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Explicitly handle flow pagination IDs before general flow processing
        if (isInteractiveSelection && selectionId != null && selectionId.startsWith("flow_page_")) {
            flowService.processFlow(contact, owner, text, selectionId, isInteractiveSelection);
            saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
            return;
        }

        // This handles both ongoing flows and new flow starts via 'trigger_flow' ID.
        boolean consumed = flowService.processFlow(contact, owner, text, selectionId, isInteractiveSelection);

        if (consumed) {
            saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
            return;
        }

        // â”€â”€ 9. Interactive Service Routing (General Browsing) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (isInteractiveSelection && selectionId != null) {
            if ("view_services".equals(selectionId) || selectionId.startsWith("page_")) {
                handleServicesPagination(contact, config, owner, selectionId);
                saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                return;
            } else if (selectionId.startsWith("srv_")) {
                handleServiceSelection(contact, config, owner, selectionId);
                saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                return;
            } else if (selectionId.startsWith("custom_list_")) {
                handleCustomSubMenuTrigger(contact, config, owner, selectionId);
                saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                return;
            } else if (selectionId.startsWith("custom_msg_")) {
                handleCustomMessageTrigger(contact, config, owner, selectionId);
                saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                return;
            } else if (selectionId.startsWith("cl") && selectionId.contains("_i")) {
                handleCustomSubMenuItemSelection(contact, config, owner, selectionId);
                saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                return;
            } else if ("about_contact".equals(selectionId) || "contact_us".equals(selectionId)) {
                handleAboutContactSelection(contact, config, owner);
                saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                return;
            }
        }

        // â”€â”€ 10. Route via Rule-based Trigger Engine (NLP-lite) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        FlowTriggerEngine triggerEngine = new FlowTriggerEngine(owner.getBusinessSubType(), objectMapper);
        FlowTriggerEngine.TriggerResult result = triggerEngine.evaluate(text);

        if (result.triggered()) {
            log.info("[Trigger] Rule '{}' hit for message '{}'", result.rule(), text);
            // Start the niche-specific conversation flow
            flowService.processFlow(contact, owner, text, "trigger_flow", true);
        } else if ("text".equals(type) && !isInteractiveSelection) {
            // â”€â”€ 11. AI RAG Intake Guardrail (Production-Grade) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            io.github.bucket4j.Bucket bucket = buckets.computeIfAbsent(contact.getWaId(), id -> createNewBucket());
            if (bucket.tryConsume(1)) {
                handleAiIntake(contact, config, owner, text, timestamp, waMessageId);
                return; // Handled by Guardrail/AI
            }
        } else {

            // â”€â”€ 12. CATCH-ALL DEFAULT â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            log.info("[Fallback] No match found. Sending default menu for contact={}", contact.getWaId());
            try {
                sendTenantMenuToContact(contact, config);
            } catch (Exception e) {
                log.error("[Fallback] Failed to send fallback menu to {}: {}", contact.getWaId(), e.getMessage());
            }
        }

        saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
    }

    /**
     * Handles the AI RAG intake logic using the RagGuardrailService.
     * Features: Deduplication, Content Filtering, Context Boosting, and Circuit Breakers.
     */
    private void handleAiIntake(Contact contact, WhatsAppConfig config, User owner, String text, long timestamp, String waMessageId) {
        // Find if last response was AI for context boost
        PageRequest pageRequest = PageRequest.of(0, 1, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "timestamp"));
        List<Message> lastOutgoing = messageRepository.findByContactAndDirection(contact, Message.Direction.OUTGOING, pageRequest);
        // We consider an outgoing message "AI" if it doesn't contain the menu signature
        boolean lastWasAi = !lastOutgoing.isEmpty() && !lastOutgoing.get(0).getContent().contains("[Sent Interactive Menu]");

        try {
            // Reset global circuit breaker if enough time passed (60s)
            long now = System.currentTimeMillis();
            if (now - lastCircuitReset.get() > 60000) {
                globalGuardrailFailures.set(0);
                lastCircuitReset.set(now);
            }

            // Triple-layer Circuit Breaker Check
            if (globalGuardrailFailures.get() >= 5) {
                log.warn("[CircuitBreaker] Global guardrail failures exceeded. Falling back to Menu.");
                sendTenantMenuToContact(contact, config);
                return;
            }

            RagGuardrailService.GuardrailResult guardrail = guardrailService.evaluate(text, contact.getWaId(), lastWasAi, owner.getBusinessSubType());

            switch (guardrail.getDecision()) {
                case CALL_AI:
                    log.info("[RAG] AI Query allowed. Reason: {}", guardrail.getReason());
                    String aiResponse = ragRetrievalService.getAiResponse(text, owner.getId());
                    if (aiResponse != null && !aiResponse.isBlank()) {
                        // Capture the user's AI query as an enquiry on the lead
                        leadRepository.findTopByContactOrderByCreatedAtDesc(contact).ifPresent(lead ->
                            leadService.appendEnquiryToLead(lead, text, "AI", "WhatsApp AI Chat")
                        );
                        sendInteractiveAiResponse(contact, aiResponse, config, owner);
                        saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                    } else {
                        sendTenantMenuToContact(contact, config);
                    }
                    break;

                case REUSE:
                    log.info("[RAG] Deduplication hit. Reusing last AI response. Key: {}", guardrail.getContextKey());
                    if (!lastOutgoing.isEmpty()) {
                        sendInteractiveAiResponse(contact, lastOutgoing.get(0).getContent(), config, owner);
                        saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                    } else {
                        // Fallback if message history was cleared
                        String fallbackAi = ragRetrievalService.getAiResponse(text, owner.getId());
                        sendInteractiveAiResponse(contact, fallbackAi, config, owner);
                        saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                    }
                    break;

                case CLARIFY:
                    log.info("[RAG] Signal weak. Sending contextual clarification: {}", guardrail.getSuggestion());
                    sendInteractiveAiResponse(contact, guardrail.getSuggestion(), config, owner);
                    saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                    break;

                case IGNORE:
                    log.info("[RAG] Decision: IGNORE. Reason: {}", guardrail.getReason());
                    // Soft ignore warning on 3rd junk
                    if (guardrail.getReason().contains("spam")) {
                        whatsappClient.sendMessage(contact.getWaId(), "Please enter a valid question so I can help you! \uD83D\uDE42", config.getAccessToken(), config.getPhoneNumberId());
                    }
                    break;

                case WARNING:
                    log.info("[Abuse] Sending respectful warning to {}", contact.getWaId());
                    whatsappClient.sendMessage(contact.getWaId(), guardrail.getSuggestion(), config.getAccessToken(), config.getPhoneNumberId());
                    saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                    break;

                case MENU:
                default:
                    log.info("[RAG] Guardrail rejected query (low signal). Sending menu. Reason: {}", guardrail.getReason());
                    sendTenantMenuToContact(contact, config);
                    saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                    break;
            }

        } catch (Exception e) {
            log.error("[Guardrail-Fail] System error in guardrail engine", e);
            globalGuardrailFailures.incrementAndGet();
            
            // Intelligent Fail-Open: If text looks like it has intent words, try AI anyway.
            if (text.length() > 5 && (text.contains("price") || text.contains("cost") || text.contains("time") || text.contains("book"))) { 
                 String aiResponse = ragRetrievalService.getAiResponse(text, owner.getId());
                 if (aiResponse != null) {
                     whatsappClient.sendMessage(contact.getWaId(), aiResponse, config.getAccessToken(), config.getPhoneNumberId());
                     saveIncomingMessage(contact, text, timestamp, waMessageId, owner);
                     return;
                 }
            }
            sendTenantMenuToContact(contact, config);
        }
    }

    private void saveIncomingMessage(Contact contact, String text, long timestamp, String waMessageId, User owner) {
        Message message = Message.builder()
                .contact(contact)
                .content(text)
                .direction(Message.Direction.INCOMING)
                .timestamp(LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault()))
                .waMessageId(waMessageId)
                .build();
        messageRepository.save(message);

        // â”€â”€ Push real-time update via WebSocket â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        Map<String, Object> wsPayload = new HashMap<>();
        wsPayload.put("id",          message.getId().toString());
        wsPayload.put("contactId",   contact.getId().toString());
        wsPayload.put("contactName", contact.getName());
        wsPayload.put("content",     text);
        wsPayload.put("direction",   "INCOMING");
        wsPayload.put("timestamp",   message.getTimestamp().toString());
        messagingTemplate.convertAndSend("/topic/" + owner.getId() + "/messages", wsPayload);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //  Auto-fetch WhatsApp profile name from webhook contacts[] array
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private String extractProfileName(JsonNode contactsNode, String waId) {
        if (contactsNode != null && contactsNode.isArray()) {
            for (JsonNode c : contactsNode) {
                if (waId.equals(c.path("wa_id").asText())) {
                    String name = c.path("profile").path("name").asText();
                    if (name != null && !name.isBlank()) return name;
                }
            }
        }
        return null;
    }

    private Contact resolveContact(String waId, String profileName, User owner) {
        // â”€â”€ TENANT-SCOPED lookup: same phone number can exist under different tenants â”€â”€
        Optional<Contact> existing = contactRepository.findByWaIdAndOwner(waId, owner);
        if (existing.isPresent()) {
            Contact c = existing.get();
            // Upgrade placeholder name to real WA profile name if available
            if (profileName != null && (c.getName() == null || c.getName().startsWith("WhatsApp User"))) {
                c.setName(profileName);
                contactRepository.save(c);
            }
            return c;
        }
        // Create a NEW contact scoped to THIS tenant only
        Contact newContact = Contact.builder()
                .waId(waId)
                .name(profileName != null ? profileName : "WhatsApp User " + waId)
                .source("WhatsApp")
                .owner(owner)  // â† always tied to exact tenant
                .build();
        return contactRepository.save(newContact);
    }

    /**
     * Determines the enquiry type based on message context to decide lead creation strategy.
     * 
     * @param text The message text
     * @param type The message type (text, interactive, etc.)
     * @param isInteractiveSelection Whether this is an interactive selection
     * @param contact The contact making the enquiry
     * @return The enquiry type: "NEW_ENQUIRY" for distinct enquiries, "ONGOING" for continuing conversations
     */
    private String determineEnquiryType(String text, String type, boolean isInteractiveSelection, Contact contact) {
        // Check if this is a new enquiry flow trigger
        if (isInteractiveSelection) {
            // Interactive selections that start new flows should create new leads
            // This includes service selections, flow triggers, etc.
            return "NEW_ENQUIRY";
        }
        
        // Check for greeting patterns that indicate a fresh start
        String lower = text.trim().toLowerCase();
        boolean isGreeting = lower.matches("^(hi|hello|hey|namaste|hi there|hello there)$");
        boolean isNavCommand = lower.matches("^(menu|options|help|start|services|show)$");
        
        if (isGreeting || isNavCommand) {
            return "NEW_ENQUIRY";
        }
        
        // Check if this looks like a new business enquiry
        if (containsNewEnquiryKeywords(text)) {
            return "NEW_ENQUIRY";
        }
        
        // Default to ongoing conversation
        return "ONGOING";
    }
    
    /**
     * Checks if the message contains keywords that indicate a new business enquiry.
     */
    private boolean containsNewEnquiryKeywords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        
        String lower = text.toLowerCase();
        
        // Keywords that typically indicate new enquiries
        String[] newEnquiryKeywords = {
            "price", "cost", "quote", "estimate", "booking", "book", "appointment",
            "service", "available", "timing", "schedule", "interested", "need",
            "want", "looking for", "enquiry", "inquiry", "information", "details"
        };
        
        for (String keyword : newEnquiryKeywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }

    private boolean resolveAndMaybeSaveLead(Contact contact, User owner, String enquiryType) {
        // Validate lead creation scenario
        try {
            leadService.validateLeadCreation(contact, owner, enquiryType);
        } catch (Exception e) {
            log.error("[Lead] Validation failed for contact {}: {}", contact.getWaId(), e.getMessage());
            return false; // Don't create lead if validation fails
        }
        
        // For new enquiry flows, always create a new lead
        if ("NEW_ENQUIRY".equals(enquiryType)) {
            Lead lead = Lead.builder()
                    .contact(contact)
                    .status(Lead.LeadStatus.NEW)
                    .owner(owner)
                    .build();
            leadRepository.save(lead);
            log.info("[Lead] Created new lead for NEW_ENQUIRY from contact: {}", contact.getWaId());
            return true;
        }
        
        // For ongoing conversations, reuse active lead
        List<Lead.LeadStatus> closedStatuses = List.of(
                Lead.LeadStatus.CLOSED_WON, Lead.LeadStatus.CLOSED_LOST);

        Optional<Lead> activeLead = leadRepository
                .findTopByContactAndStatusNotInOrderByCreatedAtDesc(contact, closedStatuses);

        if (activeLead.isEmpty()) {
            // No active lead — create a fresh one
            Lead lead = Lead.builder()
                    .contact(contact)
                    .status(Lead.LeadStatus.NEW)
                    .owner(owner)
                    .build();
            leadRepository.save(lead);
            log.info("[Lead] Created new lead (no active lead found) for contact: {}", contact.getWaId());
            return true; // isNew
        }
        
        log.info("[Lead] Reusing existing active lead for contact: {}", contact.getWaId());
        return false; // existing active lead reused
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //  Greeting: welcome text + interactive menu
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private void sendGreetingWithMenu(Contact contact, WhatsAppConfig config, User owner, boolean isNewLead) {
        String customWelcome = config.getWelcomeMessage();
        String customReturn  = config.getReturningMessage();
        String greeting;

        if (isNewLead && customWelcome != null && !customWelcome.isBlank()) {
            greeting = interpolateVariables(customWelcome, contact, owner);
        } else if (!isNewLead && customReturn != null && !customReturn.isBlank()) {
            greeting = interpolateVariables(customReturn, contact, owner);
        } else {
            // Fallback to hardcoded template
            String name = contact.getName();
            String displayName = (name == null || name.startsWith("WhatsApp User")) ? "there" : name.split(" ")[0];
            greeting = "\uD83D\uDC4B Hello" + (displayName.equals("there") ? "" : " " + displayName) + "!\n"
                    + "Welcome to *" + (owner.getBusinessName() != null ? owner.getBusinessName() : "our service") + "*.\n\n"
                    + "Please choose from our services below \uD83D\uDC47";
        }

        log.info("[Greeting] Sending {} greeting to {}", (isNewLead ? "NEW" : "RETURNING"), contact.getWaId());
        sendTenantMenuToContact(contact, config, greeting);
    }

    private String interpolateVariables(String text, Contact contact, User owner) {
        if (text == null) return null;
        
        String name = contact.getName();
        String displayName = (name == null || name.startsWith("WhatsApp User")) ? "Guest" : name.split(" ")[0];
        String bizName = (owner.getBusinessName() != null) ? owner.getBusinessName() : "our business";

        return text.replace("{{name}}", displayName)
                   .replace("{{business}}", bizName);
    }

    public void sendTenantMenuToContact(Contact contact, WhatsAppConfig config) {
        sendTenantMenuToContact(contact, config, null);
    }

    public void sendTenantMenuToContact(Contact contact, WhatsAppConfig config, String overrideBodyText) {
        MenuDto menu;

        if (config.getInteractiveMenuJson() == null || config.getInteractiveMenuJson().isBlank()) {
            // â”€â”€ No menu configured â†’ Auto-generate default from sub-category â”€â”€â”€â”€â”€
            log.info("[Menu] No custom menu found for tenant {}. Using auto-default menu.", config.getUser().getId());
            menu = buildDefaultMenu(config.getUser());
        } else {
            // â”€â”€ Use tenant-configured menu â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            try {
                menu = objectMapper.readValue(config.getInteractiveMenuJson(), MenuDto.class);
                validateMenu(menu);
                // â”€â”€ CRITICAL FIX: Always refresh the trigger_flow label from the live engine.
                // This ensures that if the tenant changes their sub-category, the correct
                // label is sent to WhatsApp without requiring a manual re-save of the menu.
                String menuType = menu.getType();
                String freshTriggerTitle = "button".equals(menuType)
                        ? templateEngine.getTriggerButtonLabel(config.getUser().getBusinessSubType())
                        : templateEngine.getTriggerListLabel(config.getUser().getBusinessSubType());
                if (menu.getSections() != null) {
                    for (com.chatcrmlite.backend.dto.MenuDto.MenuSectionDto section : menu.getSections()) {
                        if (section.getRows() != null) {
                            for (com.chatcrmlite.backend.dto.MenuDto.MenuRowDto row : section.getRows()) {
                                if ("trigger_flow".equals(row.getId())) {
                                    // Meta limits: button title <= 20 chars, list row title <= 24 chars
                                    int maxLen = "button".equals(menuType) ? 20 : 24;
                                    row.setTitle(freshTriggerTitle.length() > maxLen
                                            ? freshTriggerTitle.substring(0, maxLen)
                                            : freshTriggerTitle);
                                    log.info("[Menu] Refreshed trigger_flow label to: [{}] (len={}) for sub-cat: [{}]",
                                            row.getTitle(), row.getTitle().length(), config.getUser().getBusinessSubType());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Menu] Saved menu is invalid ({}), falling back to default.", e.getMessage());
                menu = buildDefaultMenu(config.getUser());
            }
        }

        // Apply custom body text if provided (this fixes the missing greeting)
        if (overrideBodyText != null && !overrideBodyText.isBlank()) {
            menu.setBodyText(overrideBodyText);
        }

        // â”€â”€ DYNAMIC INJECTION: Handle SOS and Optional features â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        injectDynamicButtons(menu, config);

        // â”€â”€ CRITICAL: Remove the "Our Services" header title as requested â”€â”€
        menu.setTitle(null); 

        try {
            whatsappClient.sendInteractiveMenu(contact.getWaId(), menu,
                    config.getAccessToken(), config.getPhoneNumberId());
        } catch (Exception e) {
            log.warn("[Menu] Failed to send menu to {}: {}", contact.getWaId(), e.getMessage());
        }
    }

    /**
     * Builds a sensible 3-button default menu based on the tenant's business sub-category.
     * Button 1 = fixed flow trigger (non-editable, sub-category-specific).
     * Button 2 = "ðŸ“‹ View Services".
     * Button 3 = "ðŸ“ž Contact Us".
     */
    private MenuDto buildDefaultMenu(User owner) {
        String subCategory = owner.getBusinessSubType();
        String triggerLabel = templateEngine.getTriggerButtonLabel(subCategory);
        WhatsAppConfig config = whatsappConfigRepository.findByUserId(owner.getId()).orElse(null);

        // Truncate to 20 chars (Meta limit) just in case
        if (triggerLabel.length() > 20) triggerLabel = triggerLabel.substring(0, 20);

        List<com.chatcrmlite.backend.dto.MenuDto.MenuRowDto> buttons = new ArrayList<>();
        buttons.add(com.chatcrmlite.backend.dto.MenuDto.MenuRowDto.builder()
                .id("trigger_flow").title(triggerLabel).build());
        buttons.add(com.chatcrmlite.backend.dto.MenuDto.MenuRowDto.builder()
                .id("view_services").title(templateEngine.getServicesLabel(subCategory)).build());

        // 3rd Button injection logic for Default Menu
        if (config != null) {
            injectThirdButton(buttons, config);
        }

        com.chatcrmlite.backend.dto.MenuDto.MenuSectionDto section =
            com.chatcrmlite.backend.dto.MenuDto.MenuSectionDto.builder()
                .rows(buttons)
                .build();

        return MenuDto.builder()
                .type("button")
                .title("How can we help you today?")
                .sections(List.of(section))
                .build();
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //  Public APIs (used by MessageController)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    public void sendMessage(UUID contactId, String text, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        WhatsAppConfig config = whatsappConfigRepository.findByUserId(owner.getId())
                .orElseThrow(() -> new RuntimeException("WhatsApp configuration not found for user"));

        String waMessageId = whatsappClient.sendMessage(
                contact.getWaId(), text, config.getAccessToken(), config.getPhoneNumberId());

        Message message = Message.builder()
                .contact(contact)
                .content(text)
                .direction(Message.Direction.OUTGOING)
                .timestamp(LocalDateTime.now())
                .waMessageId(waMessageId)
                .build();
        messageRepository.save(message);
    }

    public void sendTenantMenu(UUID contactId, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        WhatsAppConfig config = whatsappConfigRepository.findByUserId(owner.getId())
                .orElseThrow(() -> new RuntimeException("WhatsApp config not found"));

        MenuDto menu;
        if (config.getInteractiveMenuJson() == null || config.getInteractiveMenuJson().isBlank()) {
            log.info("[API-Menu] No custom menu for tenant {}. Sending default.", owner.getId());
            menu = buildDefaultMenu(owner);
        } else {
            try {
                menu = objectMapper.readValue(config.getInteractiveMenuJson(), MenuDto.class);
                validateMenu(menu);
            } catch (Exception e) {
                log.warn("[API-Menu] Custom menu invalid for {}, falling back to default.", owner.getId());
                menu = buildDefaultMenu(owner);
            }
        }

        try {
            String waId = whatsappClient.sendInteractiveMenu(
                    contact.getWaId(), menu, config.getAccessToken(), config.getPhoneNumberId());
            Message msg = Message.builder()
                    .contact(contact)
                    .content("[Sent Interactive Menu] " + (menu.getTitle() != null ? menu.getTitle() : ""))
                    .direction(Message.Direction.OUTGOING)
                    .timestamp(LocalDateTime.now())
                    .waMessageId(waId)
                    .build();
            messageRepository.save(msg);
        } catch (Exception e) {
            log.error("[API-Menu] Failed to send menu to {}: {}", contact.getWaId(), e.getMessage());
            throw new RuntimeException("Failed to send menu: " + e.getMessage());
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //  Menu Validation (unchanged)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    public void validateMenu(MenuDto menu) {
        boolean isButton = "button".equals(menu.getType());
        int totalRows = 0;
        if (menu.getSections() == null || menu.getSections().isEmpty())
            throw new RuntimeException("Menu must have at least one section.");
        if (isButton && menu.getSections().size() > 1)
            throw new RuntimeException("Button menus can only have one section.");
        for (MenuDto.MenuSectionDto s : menu.getSections()) {
            if (s.getRows() == null || s.getRows().isEmpty())
                throw new RuntimeException("Sections must contain at least one item.");
            if (!isButton && s.getRows().size() > 10)
                throw new RuntimeException("Too many rows in section.");
            if (isButton && s.getRows().size() > 3)
                throw new RuntimeException("Button menus can only have up to 3 buttons.");
            for (MenuDto.MenuRowDto r : s.getRows()) {
                if (r.getTitle() == null || r.getTitle().trim().isEmpty())
                    throw new RuntimeException("Item title cannot be empty.");
                if (r.getTitle().length() > 25)
                    throw new RuntimeException("Title too long (max 25 characters).");
                if (!isButton && r.getDescription() != null && r.getDescription().length() > 72)
                    throw new RuntimeException("Description too long (max 72 characters).");
                totalRows++;
            }
        }
        if (!isButton && totalRows > 10)
            throw new RuntimeException("Max 10 items allowed total.");
        if (isButton && totalRows > 3)
            throw new RuntimeException("Max 3 items allowed for button menus.");
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //  Dynamic Services & Products
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private void handleServicesPagination(Contact contact, WhatsAppConfig config, User owner, String selectionId) {
        int pageIndex = 0;
        if (selectionId.startsWith("page_")) {
            try {
                // expecting format: "page_1_services", so index = 1 means 2nd page
                pageIndex = Integer.parseInt(selectionId.split("_")[1]);
            } catch (Exception e) {
                log.warn("Failed to parse page index from selectionId: {}", selectionId);
            }
        }

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(pageIndex, 9);
        org.springframework.data.domain.Page<com.chatcrmlite.backend.models.BusinessService> servicesPage = 
                businessServiceRepository.findByOwner(owner, pageable);

        if (servicesPage.isEmpty() && pageIndex == 0) {
            whatsappClient.sendMessage(contact.getWaId(), "No services or products available right now.", 
                    config.getAccessToken(), config.getPhoneNumberId());
            return;
        }

        List<MenuDto.MenuRowDto> rows = new ArrayList<>();
        for (com.chatcrmlite.backend.models.BusinessService srv : servicesPage.getContent()) {
            String title = srv.getName() != null ? srv.getName() : "Service";
            if (title.length() > 24) title = title.substring(0, 24);
            
            rows.add(MenuDto.MenuRowDto.builder()
                    .id("srv_" + srv.getId().toString())
                    .title(title)
                    .build());
        }

        if (servicesPage.hasNext()) {
            rows.add(MenuDto.MenuRowDto.builder()
                    .id("page_" + (pageIndex + 1) + "_services")
                    .title("Next \u25B6\uFE0F")
                    .description("View more services")
                    .build());
        } else if (pageIndex > 0) {
            rows.add(MenuDto.MenuRowDto.builder()
                    .id("view_services")
                    .title("\u25C0\uFE0F Back to page 1")
                    .build());
        }

        String subCategory = owner.getBusinessSubType();
        String dynLabel = templateEngine.getServicesLabel(subCategory);

        MenuDto.MenuSectionDto section = MenuDto.MenuSectionDto.builder()
                .title("Available Options")
                .rows(rows)
                .build();

        MenuDto menu = MenuDto.builder()
                .type("list")
                .title("Our Offerings")
                .bodyText("Please choose an option you want to learn more about:")
                .button("View Options")
                .sections(List.of(section))
                .build();

        try {
            whatsappClient.sendInteractiveMenu(contact.getWaId(), menu, config.getAccessToken(), config.getPhoneNumberId());
        } catch (Exception e) {
            log.error("Failed to send services menu to {}: {}", contact.getWaId(), e.getMessage());
        }
    }

    private String getPublicImageUrl(String localUrl) {
        if (localUrl == null) return null;
        if (publicAppUrl != null && !publicAppUrl.isEmpty()) {
            return localUrl.replace("http://localhost:8080", publicAppUrl);
        }
        return localUrl;
    }

    private void handleServiceSelection(Contact contact, WhatsAppConfig config, User owner, String selectionId) {
        String serviceIdStr = selectionId.replace("srv_", "");
        try {
            UUID srvId = UUID.fromString(serviceIdStr);
            com.chatcrmlite.backend.models.BusinessService srv = businessServiceRepository.findByIdAndOwner(srvId, owner).orElse(null);

            if (srv == null) {
                whatsappClient.sendMessage(contact.getWaId(), "Oops! Service not found.", config.getAccessToken(), config.getPhoneNumberId());
                return;
            }

            // Prepare Dynamic Label (Trigger Flow)
            String triggerLabel = templateEngine.getTriggerButtonLabel(owner.getBusinessSubType());
            if (triggerLabel.length() > 20) triggerLabel = triggerLabel.substring(0, 20);

            // 1. Prepare Single-Bubble Message Content
            String headerImageUrl = null;
            if (srv.getImageUrl() != null && !srv.getImageUrl().isBlank()) {
                headerImageUrl = getPublicImageUrl(srv.getImageUrl());
            }

            // Bold Service Name at the top of the body text
            String cleanName = srv.getName() != null ? srv.getName() : "Service Details";
            String cleanDesc = srv.getDescription() != null ? srv.getDescription() : "Please see the options below:";
            
            String bodyText = "*" + cleanName + "*\n\n" + cleanDesc;
            if (bodyText.length() > 1024) bodyText = bodyText.substring(0, 1021) + "...";

            MenuDto menu = MenuDto.builder()
                    .type("button")
                    .headerImageUrl(headerImageUrl)
                    .bodyText(bodyText)
                    .sections(List.of(
                            MenuDto.MenuSectionDto.builder().rows(List.of(
                                    MenuDto.MenuRowDto.builder().id("trigger_flow").title(triggerLabel).build(),
                                    MenuDto.MenuRowDto.builder().id("view_services").title("\u25C0\uFE0F Back to Services").build()
                            )).build()
                    )).build();

            // 2. Send the unified message
            whatsappClient.sendInteractiveMenu(contact.getWaId(), menu, config.getAccessToken(), config.getPhoneNumberId());

        } catch (Exception e) {
            log.error("Error handling service selection: {}", e.getMessage());
        }
    }

    private void injectDynamicButtons(MenuDto menu, WhatsAppConfig config) {
        if (menu.getSections() == null || menu.getSections().isEmpty()) return;
        boolean isButton = "button".equals(menu.getType());

        if (isButton) {
            List<MenuDto.MenuRowDto> rows = new ArrayList<>(menu.getSections().get(0).getRows());
            // Clear items beyond slot 2 to ensure we inject the specific 3rd button
            if (rows.size() > 2) {
                while(rows.size() > 2) rows.remove(2);
            }
            injectThirdButton(rows, config);
            menu.getSections().get(0).setRows(rows);
        } else {
            // List Menu - Ensure at least X slots are free for enabled features
            MenuDto.MenuSectionDto lastSec = menu.getSections().get(menu.getSections().size() - 1);
            List<MenuDto.MenuRowDto> rows = new ArrayList<>(lastSec.getRows());

            // 1. Calculate how many dynamic slots we NEED
            int neededSlots = 0;
            if (config.getShowSosButton() == null || config.getShowSosButton()) neededSlots++;
            if (config.getShowAboutContact() == null || config.getShowAboutContact()) neededSlots++;
            if ((config.getShowTrustButton() == null || config.getShowTrustButton()) && (config.getReviewUrl() != null && !config.getReviewUrl().isBlank())) neededSlots++;
            if ((config.getShowOfferButton() == null || config.getShowOfferButton()) && (config.getOfferText() != null && !config.getOfferText().isBlank())) neededSlots++;

            // 2. Adaptive Cleanup: If manual + needed > 10, trim manual rows
            int totalExpected = rows.size() + neededSlots;
            if (totalExpected > 10) {
                int toRemove = totalExpected - 10;
                while (toRemove > 0 && !rows.isEmpty()) {
                    rows.remove(rows.size() - 1);
                    toRemove--;
                }
            }

            // 3. Inject Trust & Reviews
            if (config.getShowTrustButton() == null || config.getShowTrustButton()) {
                if (config.getReviewUrl() != null && !config.getReviewUrl().isBlank()) {
                    if (rows.stream().noneMatch(r -> "btn_trust".equals(r.getId()))) {
                        rows.add(MenuDto.MenuRowDto.builder()
                                .id("btn_trust")
                                .title(TRUST_LABEL)
                                .description("See our client feedback")
                                .build());
                    }
                }
            }

            // 4. Inject Special Offer
            if (config.getShowOfferButton() == null || config.getShowOfferButton()) {
                if (config.getOfferText() != null && !config.getOfferText().isBlank()) {
                    if (rows.stream().noneMatch(r -> "btn_offer".equals(r.getId()))) {
                        rows.add(MenuDto.MenuRowDto.builder()
                                .id("btn_offer")
                                .title(OFFER_LABEL)
                                .description("Check our latest deals")
                                .build());
                    }
                }
            }

            // 5. Inject About Contact
            if (config.getShowAboutContact() == null || config.getShowAboutContact()) {
                if (rows.stream().noneMatch(r -> "about_contact".equals(r.getId()) || "contact_us".equals(r.getId()))) {
                    rows.add(MenuDto.MenuRowDto.builder()
                            .id("about_contact")
                            .title(ABOUT_LABEL)
                            .description("Business info & location")
                            .build());
                }
            }

            // 6. Inject Human Support (Absolute Last)
            if (config.getShowSosButton() == null || config.getShowSosButton()) {
                if (rows.stream().noneMatch(r -> "btn_sos".equals(r.getId()))) {
                    rows.add(MenuDto.MenuRowDto.builder()
                            .id("btn_sos")
                            .title(SOS_LABEL)
                            .description("Talk to a real person")
                            .build());
                }
            }

            lastSec.setRows(rows);
        }
    }

    private void injectThirdButton(List<MenuDto.MenuRowDto> buttons, WhatsAppConfig config) {
        if (buttons.size() >= 3) return; // Port limit reached or already filled

        String type = config.getThirdButtonType() != null ? config.getThirdButtonType().toUpperCase() : "ABOUT";

        switch (type) {
            case "TRUST":
                if (config.getShowTrustButton() == null || config.getShowTrustButton()) {
                    buttons.add(MenuDto.MenuRowDto.builder().id("btn_trust").title(TRUST_LABEL).build());
                    break;
                }
            case "OFFER":
                if (config.getShowOfferButton() == null || config.getShowOfferButton()) {
                    buttons.add(MenuDto.MenuRowDto.builder().id("btn_offer").title(OFFER_LABEL).build());
                    break;
                }
            case "SOS":
                if (config.getShowSosButton() == null || config.getShowSosButton()) {
                    buttons.add(MenuDto.MenuRowDto.builder().id("btn_sos").title(SOS_LABEL).build());
                    break;
                }
            default: // Default to About if enabled
                if (config.getShowAboutContact() == null || config.getShowAboutContact()) {
                    buttons.add(MenuDto.MenuRowDto.builder().id("about_contact").title(ABOUT_LABEL).build());
                }
                break;
        }
    }

    private void injectAboutContactOption(MenuDto menu) {
        // Redundant - Logic merged into injectDynamicButtons
    }

    private void handleAboutContactSelection(Contact contact, WhatsAppConfig config, User owner) {
        // 1. Send Text Info
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(owner.getBusinessName() != null ? owner.getBusinessName() : "Our Business").append("*\n\n");
        
        if (owner.getAboutUs() != null && !owner.getAboutUs().isBlank()) {
            sb.append(owner.getAboutUs()).append("\n\n");
        }
        
        sb.append("\uD83D\uDCDD *Contact Details:*\n");
        if (owner.getPhone() != null) sb.append("\uD83D\uDCDE Phone: ").append(owner.getPhone()).append("\n");
        if (owner.getEmail() != null) sb.append("\uD83D\uDCE7 Email: ").append(owner.getEmail()).append("\n");
        if (owner.getAddress() != null) sb.append("\uD83D\uDCCD Address: ").append(owner.getAddress()).append("\n");

        whatsappClient.sendMessage(contact.getWaId(), sb.toString(), config.getAccessToken(), config.getPhoneNumberId());

        // 2. Send Location Map (if configured)
        if (owner.getLatitude() != null && owner.getLongitude() != null) {
            whatsappClient.sendLocation(
                contact.getWaId(), 
                owner.getLatitude(), 
                owner.getLongitude(), 
                owner.getBusinessName(), 
                owner.getAddress(), 
                config.getAccessToken(), 
                config.getPhoneNumberId()
            );
        }
    }

    private void handleCustomSubMenuTrigger(Contact contact, WhatsAppConfig config, User owner, String selectionId) {
        if (config.getCustomSubMenusJson() == null || config.getCustomSubMenusJson().isBlank()) return;

        try {
            JsonNode root = objectMapper.readTree(config.getCustomSubMenusJson());
            if (!root.isArray()) return;

            for (JsonNode sub : root) {
                if (selectionId.equals(sub.path("id").asText())) {
                    String title = sub.path("headerTitle").asText("Menu");
                    String body = sub.path("bodyText").asText("Please select an option:");
                    String headerImg = sub.path("headerImageUrl").asText();
                    JsonNode items = sub.path("items");

                    List<MenuDto.MenuRowDto> rows = new ArrayList<>();
                    for (int i = 0; i < items.size(); i++) {
                        JsonNode item = items.get(i);
                        String listNum = selectionId.replace("custom_list_", "");
                        String itemId = "cl" + listNum + "_i" + i;
                        
                        rows.add(MenuDto.MenuRowDto.builder()
                                .id(itemId)
                                .title(item.path("title").asText())
                                .description(item.path("desc").asText())
                                .build());
                    }

                    MenuDto menu = MenuDto.builder()
                            .type("list")
                            .title(title)
                            .bodyText(body)
                            .headerImageUrl(getPublicImageUrl(headerImg))
                            .button("View Options")
                            .sections(List.of(MenuDto.MenuSectionDto.builder().rows(rows).build()))
                            .build();

                    whatsappClient.sendInteractiveMenu(contact.getWaId(), menu, config.getAccessToken(), config.getPhoneNumberId());
                    return;
                }
            }
        } catch (Exception e) {
            log.error("[CustomMenu] Failed to send sub-menu: {}", e.getMessage());
        }
    }

    private void handleCustomSubMenuItemSelection(Contact contact, WhatsAppConfig config, User owner, String selectionId) {
        // ID format: cl1_i0
        try {
            String[] parts = selectionId.replace("cl", "").split("_i");
            int listIdx = Integer.parseInt(parts[0]) - 1;
            int itemIdx = Integer.parseInt(parts[1]);

            JsonNode root = objectMapper.readTree(config.getCustomSubMenusJson());
            JsonNode sub = root.get(listIdx);
            if (sub != null) {
                JsonNode item = sub.path("items").get(itemIdx);
                if (item != null) {
                    String response = item.path("response").asText();
                    String imgUrl = item.path("imageUrl").asText();

                    if ((imgUrl != null && !imgUrl.isBlank()) || (response != null && !response.isBlank())) {
                        sendInteractiveAiResponse(contact, response, imgUrl, config, owner);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[CustomMenu] Failed to process selection {}: {}", selectionId, e.getMessage());
        }
    }

    private void handleCustomMessageTrigger(Contact contact, WhatsAppConfig config, User owner, String selectionId) {
        if (config.getCustomMessagesJson() == null || config.getCustomMessagesJson().isBlank()) return;
        try {
            JsonNode messages = objectMapper.readTree(config.getCustomMessagesJson());
            if (!messages.isArray()) return;

            for (JsonNode msg : messages) {
                if (selectionId.equals(msg.path("id").asText())) {
                    String text = msg.path("response").asText();
                    String imgUrl = msg.path("imageUrl").asText();

                    if ((imgUrl != null && !imgUrl.isBlank()) || (text != null && !text.isBlank())) {
                        sendInteractiveAiResponse(contact, text, imgUrl, config, owner);
                    }
                    break;
                }
            }
        } catch (Exception e) {
            log.error("[CustomMsg] Failed to process selection {}: {}", selectionId, e.getMessage());
        }
    }

    /**
     * Sends an AI response wrapped in an interactive button menu with CTAs.
     */
    private void sendInteractiveAiResponse(Contact contact, String response, WhatsAppConfig config, User owner) {
        sendInteractiveAiResponse(contact, response, null, config, owner);
    }

    /**
     * Overloaded to support optional image URLs in AI responses.
     */
    private void sendInteractiveAiResponse(Contact contact, String response, String imgUrl, WhatsAppConfig config, User owner) {
        String subCategory = owner.getBusinessSubType();
        String triggerLabel = templateEngine.getTriggerButtonLabel(subCategory);
        String servicesLabel = templateEngine.getServicesLabel(subCategory);

        // Meta limit: body text <= 1024 chars
        String body = (response != null && response.length() > 1024) ? response.substring(0, 1021) + "..." : response;

        List<com.chatcrmlite.backend.dto.MenuDto.MenuRowDto> buttons = new java.util.ArrayList<>(List.of(
            com.chatcrmlite.backend.dto.MenuDto.MenuRowDto.builder().id("trigger_flow").title(triggerLabel).build(),
            com.chatcrmlite.backend.dto.MenuDto.MenuRowDto.builder().id("view_services").title(servicesLabel).build()
        ));

        com.chatcrmlite.backend.dto.MenuDto menu = com.chatcrmlite.backend.dto.MenuDto.builder()
                .type("button")
                .headerImageUrl(getPublicImageUrl(imgUrl))
                .bodyText(body)
                .sections(List.of(com.chatcrmlite.backend.dto.MenuDto.MenuSectionDto.builder().rows(buttons).build()))
                .build();

        try {
            whatsappClient.sendInteractiveMenu(contact.getWaId(), menu, config.getAccessToken(), config.getPhoneNumberId());
        } catch (Exception e) {
            log.error("[RAG-Interactive] Failed to send interactive AI response: {}", e.getMessage());
            // Last resort: try sending as plain text if interactive fails
            whatsappClient.sendMessage(contact.getWaId(), body, config.getAccessToken(), config.getPhoneNumberId());
        }
    }
}
