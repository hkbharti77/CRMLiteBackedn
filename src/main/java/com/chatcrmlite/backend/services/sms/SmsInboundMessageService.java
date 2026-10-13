package com.chatcrmlite.backend.services.sms;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.sms.SmsMessage;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.SmsMessageRepository;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsInboundMessageService {

    private final MessageRepository messageRepository;
    private final ContactRepository contactRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final SmsMessageRepository smsMessageRepository;
    private final DistributedWebSocketPublisher webSocketPublisher;

    @Transactional
    public Message processInboundSms(String providerType, String fromPhone, String toPhone, String messageId, String bodyText) {
        if (fromPhone == null || fromPhone.isBlank() || bodyText == null) {
            log.warn("Invalid inbound SMS received: fromPhone={}, body={}", fromPhone, bodyText);
            return null;
        }

        String normalizedFrom = fromPhone.trim();
        String dedupeId = "sms_" + (messageId != null && !messageId.isBlank() ? messageId : UUID.randomUUID().toString());

        // Idempotency: skip already processed message
        Optional<Message> existing = messageRepository.findByWaMessageId(dedupeId);
        if (existing.isPresent()) {
            log.info("Inbound SMS already processed: waMessageId={}", dedupeId);
            return existing.get();
        }

        // 1. Resolve Tenant
        Tenant tenant = resolveTenantForInbound(normalizedFrom, toPhone);
        if (tenant == null) {
            log.warn("Could not resolve tenant for incoming SMS from={}", normalizedFrom);
            return null;
        }

        // Resolve Tenant User / Owner
        User owner = userRepository.findFirstByTenantIdAndRole(tenant.getId(), User.Role.OWNER)
                .or(() -> userRepository.findAllByTenant(tenant).stream().findFirst())
                .orElse(null);

        // 2. Resolve or Create Contact
        Contact contact = contactRepository.findByTenantIdAndWaId(tenant.getId(), normalizedFrom)
                .orElseGet(() -> {
                    Contact newContact = new Contact();
                    newContact.setTenant(tenant);
                    newContact.setWaId(normalizedFrom);
                    newContact.setName("SMS Contact " + normalizedFrom);
                    newContact.setSource("SMS");
                    if (owner != null) {
                        newContact.setOwner(owner);
                    }
                    return contactRepository.save(newContact);
                });

        // 3. Create CRM Chat Message
        Message message = new Message();
        message.setWaMessageId(dedupeId);
        message.setContact(contact);
        message.setOwner(contact.getOwner() != null ? contact.getOwner() : owner);
        message.setTenant(tenant);
        message.setContent(bodyText.trim());
        message.setDirection(Message.Direction.INCOMING);
        message.setTimestamp(LocalDateTime.now());
        message.setDeliveryStatus(Message.DeliveryStatus.DELIVERED);

        Message saved = messageRepository.save(message);
        log.info("Persisted inbound SMS message id={} for contact id={}, tenant={}",
                saved.getId(), contact.getId(), tenant.getId());

        // 4. Real-time WebSocket event dispatch to chatroom and inbox
        try {
            Map<String, Object> payload = Map.of(
                    "id", saved.getId().toString(),
                    "waMessageId", saved.getWaMessageId(),
                    "contactId", contact.getId().toString(),
                    "channel", "SMS",
                    "direction", "INCOMING",
                    "content", saved.getContent(),
                    "timestamp", saved.getTimestamp().toString()
            );
            webSocketPublisher.publish(tenant.getId(), "/topic/chat/" + contact.getId(), payload);
            webSocketPublisher.publish(tenant.getId(), "/topic/inbox/" + tenant.getId(), payload);
        } catch (Exception e) {
            log.warn("Failed to publish WebSocket event for inbound SMS: {}", e.getMessage());
        }

        return saved;
    }

    private Tenant resolveTenantForInbound(String fromPhone, String toPhone) {
        // Priority 1: Check outbound SMS sent to this phone number
        Optional<SmsMessage> outbound = smsMessageRepository.findFirstByPhoneNumberOrderByCreatedAtDesc(fromPhone);
        if (outbound.isPresent() && outbound.get().getTenant() != null) {
            return outbound.get().getTenant();
        }

        // Priority 2: Check existing contact in database
        Optional<Contact> existingContact = contactRepository.findByWaId(fromPhone);
        if (existingContact.isPresent() && existingContact.get().getTenant() != null) {
            return existingContact.get().getTenant();
        }

        // Priority 3: Fallback to first available tenant in system
        return tenantRepository.findAll().stream().findFirst().orElse(null);
    }
}
