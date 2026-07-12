package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.ConversationStateRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.IdempotencyService;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppIngressService {

    private final ContactRepository contactRepository;
    private final MessageRepository messageRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final ConversationStateRepository conversationStateRepository;
    private final IdempotencyService idempotencyService;
    private final DistributedWebSocketPublisher distributedWebSocketPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public void resolveAndSaveIngress(ProcessingContext context) {
        try {
            JsonNode root = objectMapper.readTree(context.getPayload());
            JsonNode value = root.path("entry").get(0).path("changes").get(0).path("value");
            JsonNode messageNode = value.path("messages").get(0);
            JsonNode contactsNode = value.path("contacts");
            
            WhatsAppConfig config = whatsappConfigRepository.findByTenantId(context.getTenantId())
                    .orElseThrow(() -> new RuntimeException("Config not found"));
            User owner = config.getUser();

            // Idempotency check
            if (!idempotencyService.markAsProcessing(context.getMessageId(), context.getTenantId())) {
                log.info("[Idempotency] Duplicate message {} ignored in worker.", context.getMessageId());
                return;
            }

            // Resolve contact & save message
            String profileName = extractProfileName(contactsNode, context.getWaId());
            Contact contact = resolveContact(context.getWaId(), profileName, owner);
            
            String text = "Media / Unsupported";
            if ("text".equals(messageNode.path("type").asText())) {
                text = messageNode.path("text").path("body").asText();
            } else if ("interactive".equals(messageNode.path("type").asText())) {
                JsonNode interactive = messageNode.path("interactive");
                text = interactive.path(interactive.path("type").asText()).path("title").asText();
            }

            saveIncomingMessage(contact, text, context.getTimestamp() / 1000, context.getMessageId(), owner);
            
            // Store metadata for next stages
            context.getMetadata().put("isNewContact", messageRepository.countByContact(contact) == 1);
            context.getMetadata().put("text", text);
            context.getMetadata().put("type", messageNode.path("type").asText());
            // Flag whether this contact is currently mid-flow so the orchestrator
            // can route free-text replies to the flow worker instead of the AI worker.
            context.getMetadata().put("hasActiveFlow", conversationStateRepository.existsByContact(contact));
            
            log.info("✅ [Ingress-Stage] Resolved contact {} and saved message {}", context.getWaId(), context.getMessageId());
        } catch (Exception e) {
            log.error("❌ [Ingress-Stage] Failed for {}", context.getMessageId(), e);
            throw new RuntimeException(e);
        }
    }

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
        Optional<Contact> existing = contactRepository.findByWaIdAndOwner(waId, owner);
        if (existing.isPresent()) {
            Contact c = existing.get();
            if (profileName != null && (c.getName() == null || c.getName().startsWith("WhatsApp User"))) {
                c.setName(profileName);
                contactRepository.save(c);
            }
            return c;
        }
        Contact newContact = Contact.builder()
                .waId(waId)
                .name(profileName != null ? profileName : "WhatsApp User " + waId)
                .source("WhatsApp")
                .owner(owner)
                .build();
        return contactRepository.save(newContact);
    }

    private void saveIncomingMessage(Contact contact, String text, long timestamp, String waMessageId, User owner) {
        Message message = Message.builder()
                .contact(contact)
                .owner(owner)
                .content(text)
                .direction(Message.Direction.INCOMING)
                .timestamp(LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault()))
                .waMessageId(waMessageId)
                .build();
        messageRepository.save(message);

        Map<String, Object> wsPayload = new HashMap<>();
        wsPayload.put("id",          message.getId().toString());
        wsPayload.put("contactId",   contact.getId().toString());
        wsPayload.put("contactName", contact.getName());
        wsPayload.put("content",     text);
        wsPayload.put("direction",   "INCOMING");
        wsPayload.put("timestamp",   message.getTimestamp().toString());
        distributedWebSocketPublisher.publishMessage(owner.getId(), wsPayload);
    }
}
