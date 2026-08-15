package com.chatcrmlite.backend.services.whatsapp.flows;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.flows.FlowRevision;
import com.chatcrmlite.backend.models.flows.FlowSubmission;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class FlowConfirmationService {

    private final MetaWhatsAppClient metaWhatsAppClient;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final MessageRepository messageRepository;
    private final DistributedWebSocketPublisher webSocketPublisher;

    public FlowConfirmationService(MetaWhatsAppClient metaWhatsAppClient,
                                   WhatsAppConfigRepository whatsappConfigRepository,
                                   MessageRepository messageRepository,
                                   DistributedWebSocketPublisher webSocketPublisher) {
        this.metaWhatsAppClient = metaWhatsAppClient;
        this.whatsappConfigRepository = whatsappConfigRepository;
        this.messageRepository = messageRepository;
        this.webSocketPublisher = webSocketPublisher;
    }

    /**
     * Sends revision-scoped automated confirmation message back to the customer.
     */
    public void sendConfirmation(FlowSubmission submission) {
        if (submission == null || submission.getContact() == null) return;

        Contact contact = submission.getContact();
        FlowRevision revision = submission.getRevision();
        String confirmationText = (revision != null && revision.getConfirmationMessage() != null && !revision.getConfirmationMessage().isBlank())
                ? revision.getConfirmationMessage()
                : "Thank you! We have received your submission.";

        UUID tenantId = (submission.getTenant() != null) ? submission.getTenant().getId() : null;
        if (tenantId == null) return;

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId).orElse(null);
        if (config == null || config.getPhoneNumberId() == null || config.getAccessToken() == null) {
            log.warn("⚠️ [FlowConfirmation] Cannot send confirmation: WhatsApp account unconfigured for tenant {}", tenantId);
            return;
        }

        try {
            // Send WhatsApp Text message
            String waMessageId = metaWhatsAppClient.sendMessage(contact.getWaId(), confirmationText, config.getAccessToken(), config.getPhoneNumberId());

            // Save outgoing message in conversation timeline
            Message message = Message.builder()
                    .contact(contact)
                    .owner(contact.getOwner())
                    .content(confirmationText)
                    .direction(Message.Direction.OUTGOING)
                    .timestamp(LocalDateTime.now())
                    .waMessageId((waMessageId != null && !waMessageId.isBlank()) ? waMessageId : "flow_conf_" + System.currentTimeMillis())
                    .build();
            message.setTenant(contact.getTenant() != null ? contact.getTenant() : submission.getTenant());
            messageRepository.save(message);

            // Broadcast to Live Chat WebSocket
            Map<String, Object> wsPayload = new HashMap<>();
            wsPayload.put("id", message.getId().toString());
            wsPayload.put("contactId", contact.getId().toString());
            wsPayload.put("contactName", contact.getName());
            wsPayload.put("content", confirmationText);
            wsPayload.put("direction", "OUTGOING");
            wsPayload.put("sentiment", "NEUTRAL");
            webSocketPublisher.publishMessage(tenantId, wsPayload);

            log.info("✅ [FlowConfirmation] Sent automated confirmation to {} for Flow submission {}", contact.getWaId(), submission.getId());
        } catch (Exception e) {
            log.error("❌ [FlowConfirmation] Failed to send confirmation to {}: {}", contact.getWaId(), e.getMessage());
        }
    }
}
