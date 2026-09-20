package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.dto.catalog.SendProductResult;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.flows.FlowSendSessionRepository;
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
public class WhatsAppOutboundService {

    private static final String UNKNOWN_META_ID = "unknown_id";

    private final WhatsAppClient whatsappClient;
    private final MessageRepository messageRepository;
    private final FlowSendSessionRepository flowSendSessionRepository;
    private final DistributedWebSocketPublisher distributedWebSocketPublisher;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final OutboundSendIdempotencyService outboundSendIdempotencyService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chatcrmlite.backend.repositories.UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public WhatsAppOutboundService(WhatsAppClient whatsappClient,
                                  MessageRepository messageRepository,
                                  FlowSendSessionRepository flowSendSessionRepository,
                                  DistributedWebSocketPublisher distributedWebSocketPublisher,
                                  org.springframework.transaction.support.TransactionTemplate transactionTemplate,
                                  OutboundSendIdempotencyService outboundSendIdempotencyService) {
        this.whatsappClient = whatsappClient;
        this.messageRepository = messageRepository;
        this.flowSendSessionRepository = flowSendSessionRepository;
        this.distributedWebSocketPublisher = distributedWebSocketPublisher;
        this.transactionTemplate = transactionTemplate;
        this.outboundSendIdempotencyService = outboundSendIdempotencyService;
    }

    public WhatsAppOutboundService(WhatsAppClient whatsappClient,
                                  MessageRepository messageRepository,
                                  DistributedWebSocketPublisher distributedWebSocketPublisher) {
        this(whatsappClient, messageRepository, null, distributedWebSocketPublisher, null, null);
    }

    @org.springframework.beans.factory.annotation.Value("${app.public.url:}")
    private String appPublicUrl;

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
                            com.chatcrmlite.backend.models.flows.WhatsAppFlow flow,
                            com.chatcrmlite.backend.models.flows.FlowRevision revision, 
                            String ctaText, String screen, WhatsAppConfig config, User owner) {
        try {
            // 1. Generate unique token
            String flowToken = "fs_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            // 2. Persist the session FIRST so webhook ingress can definitively resolve it
            com.chatcrmlite.backend.models.flows.FlowSendSession session = com.chatcrmlite.backend.models.flows.FlowSendSession.builder()
                .flowToken(flowToken)
                .flow(flow)
                .revision(revision)
                .metaFlowId(revision.getMetaFlowId())
                .contact(contact)
                .expiresAt(java.time.LocalDateTime.now().plusHours(72))
                .status(com.chatcrmlite.backend.models.flows.FlowSendSessionStatus.CREATED)
                .build();
            
            // Link tenant context securely
            if (owner != null && owner.getTenant() != null) {
                session.setTenantId(owner.getTenant().getId());
            } else if (contact != null && contact.getTenant() != null) {
                session.setTenantId(contact.getTenant().getId());
            }
            
            session = flowSendSessionRepository.save(session);

            // 3. Dispatch to Meta
            String metaMessageId = whatsappClient.sendFlowMessage(
                    contact.getWaId(),
                    headerText,
                    bodyText,
                    footerText,
                    revision.getMetaFlowId(),
                    ctaText,
                    flowToken,
                    screen != null && !screen.isBlank() ? screen : "MAIN_SCREEN",
                    config.getAccessToken(),
                    config.getPhoneNumberId()
            );

            // 4. Update session as successfully sent
            session.setStatus(com.chatcrmlite.backend.models.flows.FlowSendSessionStatus.SENT);
            session.setMessageId(metaMessageId);
            flowSendSessionRepository.save(session);

            return recordOutgoing(contact, owner, "📄 [WhatsApp Flow] " + (headerText != null ? headerText : ctaText) + " (" + (bodyText != null ? bodyText : "Form") + ")", metaMessageId, "FLOW");
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
            String mediaUrl = menu.getHeaderDocumentUrl() != null && !menu.getHeaderDocumentUrl().isBlank()
                    ? menu.getHeaderDocumentUrl()
                    : menu.getHeaderImageUrl();
            return recordOutgoing(contact, owner, crmContent, metaMessageId, "INTERACTIVE", mediaUrl);
        } catch (Exception e) {
            log.error("[WhatsApp-Outbound] Failed to send INTERACTIVE reply to contact={} owner={}: {}",
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

    public String resolveCatalogDocumentUrl(com.chatcrmlite.backend.models.TenantAiCatalog catalog) {
        if (catalog == null) return null;
        String documentUrl = catalog.getCloudinaryUrl();
        if (appPublicUrl != null && !appPublicUrl.isBlank()) {
            documentUrl = appPublicUrl.replaceAll("/+$", "") + "/api/v1/public/catalogs/" + catalog.getId() + "/file";
        }
        return documentUrl;
    }

    @Transactional
    public Message sendCatalogDocument(Contact contact, com.chatcrmlite.backend.models.TenantAiCatalog catalog,
                                       String caption, WhatsAppConfig config, User owner) {
        try {
            String documentUrl = resolveCatalogDocumentUrl(catalog);

            String metaMessageId = whatsappClient.sendDocument(
                    contact.getWaId(),
                    documentUrl,
                    catalog.getFileName(),
                    caption,
                    config.getAccessToken(),
                    config.getPhoneNumberId()
            );
            return recordOutgoing(contact, owner, caption != null ? caption : catalog.getTitle(), metaMessageId, "DOCUMENT", documentUrl);
        } catch (Exception e) {
            log.error("[WhatsApp-Outbound] Failed to send CATALOG DOCUMENT reply to contact={} owner={}: {}",
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
        if (owner == null && contact != null) {
            if (contact.getOwner() != null) {
                owner = contact.getOwner();
            } else if (contact.getTenant() != null && userRepository != null) {
                java.util.List<User> users = userRepository.findAllByTenant(contact.getTenant());
                if (!users.isEmpty()) {
                    owner = users.stream()
                            .filter(u -> u.getRole() == User.Role.OWNER || u.getRole() == User.Role.ADMIN)
                            .findFirst()
                            .orElse(users.get(0));
                }
            }
        }
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
                .deliveryStatus(Message.DeliveryStatus.SENT)
                .build();

        Message saved = messageRepository.save(message);
        publishOutgoing(contact, owner, saved);

        log.info("[WhatsApp-Outbound] Sent {} reply to contact={} owner={} metaMessageId={} storedMessageId={}",
                responseType, contact.getWaId(), (owner != null ? owner.getId() : "null"), metaMessageId, storedMessageId);
        return saved;
    }

    public SendProductResult sendSingleProduct(Contact contact, String metaCatalogId,
            String productRetailerId, String productName, String bodyText,
            WhatsAppConfig config, User owner, String idempotencyKey, String executionId) {

        // 1. Meta API call strictly OUTSIDE database transaction
        String metaMessageId = whatsappClient.sendSingleProductMessage(
                contact.getWaId(),
                metaCatalogId,
                productRetailerId,
                bodyText,
                config.getAccessToken(),
                config.getPhoneNumberId()
        );

        // 2. Mark META_ACCEPTED in Redis immediately (with execution ownership token)
        boolean accepted = outboundSendIdempotencyService.markMetaAccepted(
                idempotencyKey, executionId, metaMessageId);
        if (!accepted) {
            log.warn("[WhatsApp-Outbound] Lease expired or executionId superseded for key={}", idempotencyKey);
        }

        // 3. Database transaction strictly for CRM persistence
        SendProductResult result = transactionTemplate.execute(status -> {
            Message saved = recordOutgoing(contact, owner, "Product: " + productName, metaMessageId, "PRODUCT");
            return new SendProductResult(saved.getWaMessageId(), saved.getId().toString());
        });

        // 4. DB TRANSACTION COMMITTED SUCCESSFULLY HERE!
        // Transition Redis state to SUCCEEDED post-commit
        outboundSendIdempotencyService.markSucceeded(idempotencyKey, result.messageId());

        return result;
    }

    public SendProductResult sendMultiProduct(Contact contact, String metaCatalogId,
            String headerText, String bodyText, String footerText,
            List<Map<String, Object>> sections, List<String> productNames,
            WhatsAppConfig config, User owner, String idempotencyKey, String executionId) {

        // 1. Meta API call strictly OUTSIDE database transaction
        String metaMessageId = whatsappClient.sendMultiProductMessage(
                contact.getWaId(),
                metaCatalogId,
                headerText,
                bodyText,
                footerText,
                sections,
                config.getAccessToken(),
                config.getPhoneNumberId()
        );

        // 2. Mark META_ACCEPTED in Redis immediately (with execution ownership token)
        boolean accepted = outboundSendIdempotencyService.markMetaAccepted(
                idempotencyKey, executionId, metaMessageId);
        if (!accepted) {
            log.warn("[WhatsApp-Outbound] Lease expired or executionId superseded for key={}", idempotencyKey);
        }

        // 3. Database transaction strictly for CRM persistence
        String summary = "Products: " + (productNames != null && !productNames.isEmpty() ? String.join(", ", productNames) : "Catalog Selection");
        SendProductResult result = transactionTemplate.execute(status -> {
            Message saved = recordOutgoing(contact, owner, summary, metaMessageId, "PRODUCT_LIST");
            return new SendProductResult(saved.getWaMessageId(), saved.getId().toString());
        });

        // 4. DB TRANSACTION COMMITTED SUCCESSFULLY HERE!
        // Transition Redis state to SUCCEEDED post-commit
        outboundSendIdempotencyService.markSucceeded(idempotencyKey, result.messageId());

        return result;
    }

    public SendProductResult recoverPendingCrmPersistence(Contact contact, User owner,
            String summary, String metaMessageId, String mediaType, String idempotencyKey) {
        SendProductResult result = transactionTemplate.execute(status -> {
            Message saved = recordOutgoing(contact, owner, summary, metaMessageId, mediaType);
            return new SendProductResult(saved.getWaMessageId(), saved.getId().toString());
        });

        // Committed successfully -> mark SUCCEEDED post-commit
        outboundSendIdempotencyService.markSucceeded(idempotencyKey, result.messageId());
        return result;
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
        wsPayload.put("deliveryStatus", message.getDeliveryStatus() != null ? message.getDeliveryStatus().name() : "SENT");

        UUID tenantId = (owner != null && owner.getTenant() != null) ? owner.getTenant().getId() : (owner != null ? owner.getId() : null);
        if (tenantId != null) {
            distributedWebSocketPublisher.publishMessage(tenantId, wsPayload);
        }
    }
}
