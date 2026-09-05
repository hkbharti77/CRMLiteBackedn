package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppOutboundService {

    private static final String UNKNOWN_META_ID = "unknown_id";

    private final WhatsAppClient whatsappClient;
    private final MessageRepository messageRepository;
    private final DistributedWebSocketPublisher distributedWebSocketPublisher;

    public String convertToWhatsAppMarkdown(String text) {
        if (text == null) return null;
        return text
                .replaceAll("(?s)\\*\\*(.*?)\\*\\*", "*$1*") // **bold** -> *bold*
                .replaceAll("(?s)~~(.*?)~~", "~$1~")         // ~~strike~~ -> ~strike~
                .replaceAll("(?m)^\\s*-\\s+", "• ")          // list bullets
                .replaceAll("(?m)^###\\s+(.*?)$", "*$1*")    // headers -> bold
                .replaceAll("(?m)^##\\s+(.*?)$", "*$1*")     // headers -> bold
                .replaceAll("(?m)^#\\s+(.*?)$", "*$1*");      // headers -> bold
    }

    @Transactional
    public Message sendText(Contact contact, String text, WhatsAppConfig config, User owner) {
        try {
            String formattedText = convertToWhatsAppMarkdown(text);
            String metaMessageId = whatsappClient.sendMessage(
                    contact.getWaId(),
                    formattedText,
                    config.getAccessToken(),
                    config.getPhoneNumberId()
            );
            return recordOutgoing(contact, owner, text, metaMessageId, "TEXT");
        } catch (Exception e) {
            log.error("[WhatsApp-Outbound] Failed to send TEXT reply to contact={} owner={}: {}",
                    contact.getWaId(), (owner != null ? owner.getId() : "null"), e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public Message sendFlow(Contact contact, String headerText, String bodyText, String footerText,
                            String metaFlowId, String ctaText, WhatsAppConfig config, User owner) {
        try {
            String metaMessageId = whatsappClient.sendFlowMessage(
                    contact.getWaId(),
                    headerText,
                    bodyText,
                    footerText,
                    metaFlowId,
                    ctaText,
                    "flow_session_" + contact.getId() + "_" + System.currentTimeMillis(),
                    "MAIN_SCREEN",
                    config.getAccessToken(),
                    config.getPhoneNumberId()
            );
            return recordOutgoing(contact, owner, "📄 [WhatsApp Flow] " + (headerText != null ? headerText : ctaText) + " (" + bodyText + ")", metaMessageId, "FLOW");
        } catch (Exception e) {
            log.error("[WhatsApp-Outbound] Failed to send FLOW to contact={} owner={}: {}",
                    contact.getWaId(), (owner != null ? owner.getId() : "null"), e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public Message sendInteractiveMenu(Contact contact, MenuDto menu, WhatsAppConfig config, User owner) {
        return sendInteractiveMenu(contact, menu, describeMenu(menu), config, owner);
    }

    @Transactional
    public Message sendInteractiveMenu(Contact contact, MenuDto menu, String crmContent, WhatsAppConfig config, User owner) {
        try {
            String metaMessageId = whatsappClient.sendInteractiveMenu(
                    contact.getWaId(),
                    menu,
                    config.getAccessToken(),
                    config.getPhoneNumberId()
            );
            return recordOutgoing(contact, owner, crmContent, metaMessageId, "INTERACTIVE");
        } catch (Exception e) {
            log.error("[WhatsApp-Outbound] Failed to send INTERACTIVE reply to contact={} owner={}: {}",
                    contact.getWaId(), (owner != null ? owner.getId() : "null"), e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public Message sendCatalogMessage(Contact contact, String text, WhatsAppConfig config, User owner) {
        try {
            String metaMessageId = whatsappClient.sendCatalogMessage(
                    contact.getWaId(),
                    text,
                    config.getAccessToken(),
                    config.getPhoneNumberId()
            );
            return recordOutgoing(contact, owner, "Sent Catalog", metaMessageId, "CATALOG");
        } catch (Exception e) {
            log.error("[WhatsApp-Outbound] Failed to send CATALOG reply to contact={} owner={}: {}",
                    contact.getWaId(), (owner != null ? owner.getId() : "null"), e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public Message sendImage(Contact contact, String imageUrl, String caption, WhatsAppConfig config, User owner) {
        try {
            String metaMessageId = whatsappClient.sendImage(
                    contact.getWaId(),
                    imageUrl,
                    caption,
                    config.getAccessToken(),
                    config.getPhoneNumberId()
            );
            return recordOutgoing(contact, owner, caption != null ? caption : "", metaMessageId, "IMAGE", imageUrl);
        } catch (Exception e) {
            log.error("[WhatsApp-Outbound] Failed to send IMAGE reply to contact={} owner={}: {}",
                    contact.getWaId(), (owner != null ? owner.getId() : "null"), e.getMessage(), e);
            throw e;
        }
    }

    public String describeMenu(MenuDto menu) {
        String body = menu != null && menu.getBodyText() != null ? menu.getBodyText().trim() : "";
        List<String> optionTitles = menu == null ? List.of() : menu.getSections().stream()
                .filter(section -> section != null && section.getRows() != null)
                .flatMap(section -> section.getRows().stream())
                .filter(row -> row != null && row.getTitle() != null && !row.getTitle().isBlank())
                .map(row -> row.getTitle().trim())
                .collect(Collectors.toList());

        if (optionTitles.isEmpty()) {
            return body;
        }

        String options = "Options: " + String.join(", ", optionTitles);
        if (body.isBlank()) {
            return options;
        }
        return body + "\n\n" + options;
    }

    private Message recordOutgoing(Contact contact, User owner, String content, String metaMessageId, String responseType) {
        return recordOutgoing(contact, owner, content, metaMessageId, responseType, null);
    }

    private Message recordOutgoing(Contact contact, User owner, String content, String metaMessageId, String responseType, String mediaUrl) {
        String storedMessageId = normalizeMetaMessageId(metaMessageId);
        Message message = Message.builder()
                .contact(contact)
                .owner(owner)
                .content(content)
                .direction(Message.Direction.OUTGOING)
                .timestamp(LocalDateTime.now())
                .waMessageId(storedMessageId)
                .mediaType(responseType)
                .mediaUrl(mediaUrl)
                .thumbnailUrl(mediaUrl)
                .build();

        Message saved = messageRepository.save(message);
        publishOutgoing(contact, owner, saved);

        log.info("[WhatsApp-Outbound] Sent {} reply to contact={} owner={} metaMessageId={} storedMessageId={}",
                responseType, contact.getWaId(), (owner != null ? owner.getId() : "null"), metaMessageId, storedMessageId);
        return saved;
    }

    private String normalizeMetaMessageId(String metaMessageId) {
        if (metaMessageId == null || metaMessageId.isBlank() || UNKNOWN_META_ID.equals(metaMessageId)) {
            return "local:" + UUID.randomUUID();
        }
        return metaMessageId;
    }

    private void publishOutgoing(Contact contact, User owner, Message message) {
        Map<String, Object> wsPayload = new HashMap<>();
        wsPayload.put("id", message.getId().toString());
        wsPayload.put("contactId", contact.getId().toString());
        wsPayload.put("contactName", contact.getName());
        wsPayload.put("content", message.getContent());
        wsPayload.put("direction", "OUTGOING");
        wsPayload.put("mediaUrl", message.getMediaUrl());
        wsPayload.put("mediaType", message.getMediaType());
        wsPayload.put("mimeType", message.getMimeType());
        wsPayload.put("fileName", message.getFileName());
        wsPayload.put("fileSize", message.getFileSize());
        wsPayload.put("thumbnailUrl", message.getThumbnailUrl());

        UUID tenantId = (owner != null && owner.getTenant() != null) ? owner.getTenant().getId() : (owner != null ? owner.getId() : null);
        if (tenantId != null) {
            distributedWebSocketPublisher.publishMessage(tenantId, wsPayload);
        }
    }
}
