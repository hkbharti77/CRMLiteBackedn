package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.whatsapp.ContactPhoneThreadControl;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppHandoverAuditLedger;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppPhoneNumberConfig;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppHandoverService {

    private final ContactPhoneThreadControlRepository threadControlRepository;
    private final WhatsAppHandoverAuditLedgerRepository handoverAuditLedgerRepository;
    private final WhatsAppPhoneNumberConfigRepository phoneNumberConfigRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final ContactRepository contactRepository;
    private final TenantRepository tenantRepository;
    private final DistributedWebSocketPublisher webSocketPublisher;

    @Transactional
    public void handleHandover(JsonNode entry, JsonNode change, Instant fallbackTimestamp) {
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
            log.warn("⚠️ [Handover] No tenant found for phoneNumberId={} wabaId={}", phoneNumberId, wabaId);
            return;
        }

        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return;

        // Feature-gate check
        Optional<WhatsAppPhoneNumberConfig> phoneConfigOpt = (phoneNumberId != null) ?
                phoneNumberConfigRepository.findByTenantIdAndPhoneNumberId(tenantId, phoneNumberId.trim()) : Optional.empty();

        boolean featureEnabled = phoneConfigOpt.map(c -> c.isHandoverFeatureEnabled() || c.isMetaBusinessAgentEnabled()).orElse(false);
        if (!featureEnabled) {
            log.debug("ℹ️ [Handover] Handover/MetaBusinessAgent feature disabled for phone_number_id={}. Ignoring.", phoneNumberId);
            return;
        }

        // Parse handover details
        String senderId = value.path("sender").path("id").asText(null);
        String recipientId = value.path("recipient").path("id").asText(null);
        String newOwnerAppId = value.path("new_owner_app_id").asText(null);
        String previousOwnerAppId = value.path("previous_owner_app_id").asText(null);
        String metadata = value.path("metadata").isObject() ? value.path("metadata").toString() : null;

        String controlEvent = change.path("field").asText("messaging_handovers");
        if (value.has("event")) {
            controlEvent = value.path("event").asText(controlEvent);
        }

        long rawSec = value.path("timestamp").asLong(0);
        Instant eventTimestamp = (rawSec > 0) ? Instant.ofEpochSecond(rawSec) : fallbackTimestamp;

        String targetUser = senderId != null ? senderId : recipientId;
        String fingerprint = generateFingerprint(tenantId, phoneNumberId, targetUser, newOwnerAppId, eventTimestamp.toEpochMilli());

        if (handoverAuditLedgerRepository.existsByTenantIdAndFingerprint(tenantId, fingerprint)) {
            log.info("ℹ️ [Handover] Duplicate handover event ignored fingerprint={}", fingerprint);
            return;
        }

        // Save handover audit record
        WhatsAppHandoverAuditLedger auditLedger = WhatsAppHandoverAuditLedger.builder()
                .phoneNumberId(phoneNumberId)
                .userId(targetUser)
                .waId(targetUser)
                .ownerAppId(newOwnerAppId)
                .previousOwnerAppId(previousOwnerAppId)
                .controlEvent(controlEvent)
                .metadataJson(metadata)
                .eventTimestamp(eventTimestamp)
                .fingerprint(fingerprint)
                .build();
        auditLedger.setTenant(tenant);
        handoverAuditLedgerRepository.save(auditLedger);

        // Find contact and reconcile thread ownership scoped to (contact, phoneNumberId)
        Optional<Contact> contactOpt = findContact(tenantId, targetUser);
        if (contactOpt.isPresent()) {
            Contact contact = contactOpt.get();
            ContactPhoneThreadControl threadControl = threadControlRepository
                    .findByTenantIdAndContactIdAndPhoneNumberId(tenantId, contact.getId(), phoneNumberId)
                    .orElseGet(() -> {
                        ContactPhoneThreadControl tc = ContactPhoneThreadControl.builder()
                                .contact(contact)
                                .phoneNumberId(phoneNumberId)
                                .build();
                        tc.setTenant(tenant);
                        return tc;
                    });

            threadControl.setOwnerAppId(newOwnerAppId);
            threadControl.setPreviousOwnerAppId(previousOwnerAppId);
            threadControl.setThreadControlEvent(controlEvent);
            threadControl.setThreadControlChangedAt(eventTimestamp);
            threadControl.setThreadControlSource("META_WEBHOOK");
            threadControlRepository.save(threadControl);

            log.info("✅ [Handover] Reconciled thread owner='{}' for contactId={} phone_number_id={}",
                    newOwnerAppId, contact.getId(), phoneNumberId);

            // Notify UI
            try {
                webSocketPublisher.publish(tenantId, "/topic/handover-events", java.util.Map.of(
                        "contactId", contact.getId().toString(),
                        "phoneNumberId", phoneNumberId,
                        "ownerAppId", newOwnerAppId != null ? newOwnerAppId : "",
                        "controlEvent", controlEvent
                ));
            } catch (Exception ex) {
                log.warn("⚠️ [Handover] Failed to broadcast websocket handover event: {}", ex.getMessage());
            }
        }
    }

    private Optional<Contact> findContact(UUID tenantId, String userAddress) {
        if (userAddress == null || userAddress.isBlank()) return Optional.empty();
        Optional<Contact> c = contactRepository.findByTenantIdAndWaId(tenantId, userAddress.trim());
        if (c.isPresent()) return c;
        return contactRepository.findByTenantIdAndBsuid(tenantId, userAddress.trim());
    }

    private String generateFingerprint(UUID tenantId, String phoneId, String target, String owner, long time) {
        String raw = tenantId + ":" + phoneId + ":" + target + ":" + owner + ":" + time;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
