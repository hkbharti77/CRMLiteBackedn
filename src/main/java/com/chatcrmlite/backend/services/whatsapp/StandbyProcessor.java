package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppPhoneNumberConfig;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Isolated Standby Processor.
 * When WhatsApp account is in standby mode (another business app is the active thread owner),
 * incoming customer messages arrive under the 'standby' webhook field.
 *
 * Strict Architectural Invariants:
 * - ZERO AI bot evaluation
 * - ZERO workflow orchestrator triggers
 * - ZERO keyword or menu auto-replies
 * - ZERO markAsRead (blue tick) API calls
 * - ZERO campaign triggers
 * - ZERO lead/ticket/attendance automation
 * - ZERO CRM agent auto-assignment
 *
 * It ONLY records the passive transcript into chat_messages as an observer and emits
 * a WebSocket update for human livechat visibility.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandbyProcessor {

    private final WhatsAppPhoneNumberConfigRepository phoneNumberConfigRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final ContactRepository contactRepository;
    private final MessageRepository messageRepository;
    private final TenantRepository tenantRepository;
    private final DistributedWebSocketPublisher webSocketPublisher;

    @Transactional
    public void handleStandby(JsonNode entry, JsonNode change, java.time.Instant fallbackTimestamp) {
        if (change == null) return;
        JsonNode value = change.path("value");
        if (value.isMissingNode() || value.isNull()) return;

        String phoneNumberId = value.path("metadata").path("phone_number_id").asText(null);
        String wabaId = entry.path("id").asText(null);

        UUID tenantId = null;
        if (phoneNumberId != null && !phoneNumberId.isBlank()) {
            tenantId = whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId.trim()).orElse(null);
        }
        if (tenantId == null && wabaId != null && !wabaId.isBlank()) {
            tenantId = whatsappConfigRepository.findTenantIdByWabaId(wabaId.trim()).orElse(null);
        }

        if (tenantId == null) {
            log.warn("⚠️ [Standby] No tenant found for phoneNumberId={} wabaId={}", phoneNumberId, wabaId);
            return;
        }

        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return;

        // Feature-gate check
        Optional<WhatsAppPhoneNumberConfig> phoneConfigOpt = (phoneNumberId != null) ?
                phoneNumberConfigRepository.findByTenantIdAndPhoneNumberId(tenantId, phoneNumberId.trim()) : Optional.empty();

        boolean featureEnabled = phoneConfigOpt.map(c -> c.isHandoverFeatureEnabled() || c.isMetaBusinessAgentEnabled()).orElse(false);
        if (!featureEnabled) {
            log.debug("ℹ️ [Standby] Standby/Handover feature disabled for phone_number_id={}. Discarding standby event.", phoneNumberId);
            return;
        }

        JsonNode messages = value.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            return;
        }

        for (JsonNode msgNode : messages) {
            processPassiveMessage(tenant, phoneNumberId, msgNode);
        }
    }

    private void processPassiveMessage(Tenant tenant, String phoneNumberId, JsonNode msgNode) {
        String waMessageId = msgNode.path("id").asText(null);
        String from = msgNode.path("from").asText(null);
        String type = msgNode.path("type").asText("text");

        if (waMessageId == null || from == null) {
            return;
        }

        // Deduplicate message
        if (messageRepository.findByWaMessageId(waMessageId).isPresent()) {
            log.debug("ℹ️ [Standby] Standby message {} already processed", waMessageId);
            return;
        }

        String content = "";
        if ("text".equalsIgnoreCase(type)) {
            content = msgNode.path("text").path("body").asText("");
        } else if ("button".equalsIgnoreCase(type)) {
            content = msgNode.path("button").path("text").asText("");
        } else if ("interactive".equalsIgnoreCase(type)) {
            content = msgNode.path("interactive").path("list_reply").path("title")
                    .asText(msgNode.path("interactive").path("button_reply").path("title").asText(""));
        } else {
            content = "[" + type.toUpperCase() + "]";
        }

        // Locate or build contact
        Contact contact = contactRepository.findByTenantIdAndWaId(tenant.getId(), from)
                .orElseGet(() -> {
                    Contact c = Contact.builder()
                            .waId(from)
                            .name("WhatsApp User (" + from + ")")
                            .source("STANDBY_INBOUND")
                            .build();
                    c.setTenant(tenant);
                    return contactRepository.save(c);
                });

        // Record passive message in chat_messages
        Message msg = new Message();
        msg.setWaMessageId(waMessageId);
        msg.setContact(contact);
        msg.setOwner(contact.getOwner());
        msg.setContent("[STANDBY_OBSERVER] " + content);
        msg.setDirection(Message.Direction.INCOMING);
        msg.setTimestamp(LocalDateTime.now());
        msg.setTenant(tenant);

        messageRepository.save(msg);
        log.info("👁️ [Standby] Passively recorded message waMessageId={} for contactId={} (zero automations triggered)",
                waMessageId, contact.getId());

        // Publish to WebSocket so human agents can see the external bot / agent conversation transcript live
        try {
            webSocketPublisher.publish(tenant.getId(), "/topic/standby-transcript", java.util.Map.of(
                    "contactId", contact.getId().toString(),
                    "phoneNumberId", phoneNumberId != null ? phoneNumberId : "",
                    "waMessageId", waMessageId,
                    "content", content,
                    "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception ex) {
            log.warn("⚠️ [Standby] Failed to broadcast standby websocket update: {}", ex.getMessage());
        }
    }
}
