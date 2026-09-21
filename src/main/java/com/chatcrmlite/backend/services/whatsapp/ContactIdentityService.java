package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContactIdentityService {

    private final ContactRepository contactRepository;

    @Transactional
    public void processSystemMessage(JsonNode messageNode, UUID tenantId, JsonNode valueContext) {
        if (messageNode == null) return;
        JsonNode system = messageNode.path("system");
        if (system.isMissingNode() || system.isNull()) {
            return;
        }

        String systemType = system.path("type").asText("");
        String senderWaId = messageNode.path("from").asText(null);

        log.info("🔔 [SystemMessage] Received system message type='{}' sender='{}' tenantId={}", systemType, senderWaId, tenantId);

        switch (systemType) {
            case "user_changed_number":
                handlePhoneAndBsuidMigration(system, senderWaId, tenantId);
                break;

            case "user_changed_user_id":
                handleBsuidMigration(system, senderWaId, tenantId);
                break;

            case "user_identity_changed":
                handleGeneralIdentityChange(system, senderWaId, tenantId);
                break;

            default:
                recordUnknownSystemEvent(systemType, system, senderWaId, tenantId);
                break;
        }
    }

    private void handlePhoneAndBsuidMigration(JsonNode system, String senderWaId, UUID tenantId) {
        String oldWaId = system.has("wa_id") ? system.path("wa_id").asText() : senderWaId;
        String resolvedWaId = system.path("new_wa_id").asText(null);
        if (resolvedWaId == null || resolvedWaId.isBlank()) {
            resolvedWaId = system.path("customer").asText(null);
        }

        String resolvedBsuid = system.path("user_id").asText(null);
        if (resolvedBsuid == null || resolvedBsuid.isBlank()) {
            resolvedBsuid = system.path("new_user_id").asText(null);
        }

        final String finalWaId = resolvedWaId;
        final String finalBsuid = resolvedBsuid;

        log.info("🔄 [SystemMessage] Migrating phone: oldWaId='{}' -> newWaId='{}' bsuid='{}' for tenantId={}",
                oldWaId, finalWaId, finalBsuid, tenantId);

        findContact(oldWaId, null, tenantId).ifPresent(contact -> {
            if (finalWaId != null && !finalWaId.isBlank()) {
                contact.setWaId(finalWaId.trim());
            }
            if (finalBsuid != null && !finalBsuid.isBlank()) {
                contact.setBsuid(finalBsuid.trim());
            }
            contactRepository.save(contact);
            log.info("✅ [SystemMessage] Successfully migrated contactId={} to waId='{}' bsuid='{}'",
                    contact.getId(), contact.getWaId(), contact.getBsuid());
        });
    }

    private void handleBsuidMigration(JsonNode system, String senderWaId, UUID tenantId) {
        String oldBsuid = system.path("user_id").asText(null);
        String newBsuid = system.path("new_user_id").asText(null);
        final String finalBsuid = newBsuid;

        log.info("🔄 [SystemMessage] Migrating BSUID: oldBsuid='{}' -> newBsuid='{}' sender='{}' for tenantId={}",
                oldBsuid, finalBsuid, senderWaId, tenantId);

        findContact(senderWaId, oldBsuid, tenantId).ifPresent(contact -> {
            if (finalBsuid != null && !finalBsuid.isBlank()) {
                contact.setBsuid(finalBsuid.trim());
            }
            contactRepository.save(contact);
            log.info("✅ [SystemMessage] Successfully updated contactId={} BSUID='{}'",
                    contact.getId(), contact.getBsuid());
        });
    }

    private void handleGeneralIdentityChange(JsonNode system, String senderWaId, UUID tenantId) {
        // Compatibility / fallback parser for unconfirmed/future transitions:
        // Must validate exact expected migration fields. Non-mutating by default!
        boolean hasExplicitNewWaId = system.hasNonNull("new_wa_id") && !system.path("new_wa_id").asText().isBlank();
        boolean hasExplicitNewUserId = system.hasNonNull("new_user_id") && !system.path("new_user_id").asText().isBlank();

        if (!hasExplicitNewWaId && !hasExplicitNewUserId) {
            log.warn("⚠️ [SystemMessage] Unconfirmed event 'user_identity_changed' received without recognized migration schema. Non-mutating audit: sender='{}' tenantId={} payload={}",
                    senderWaId, tenantId, system);
            return;
        }

        String newWaId = hasExplicitNewWaId ? system.path("new_wa_id").asText().trim() : null;
        String newBsuid = hasExplicitNewUserId ? system.path("new_user_id").asText().trim() : null;

        log.info("🔄 [SystemMessage] Validated compatibility identity change: sender='{}' newWaId='{}' newBsuid='{}'",
                senderWaId, newWaId, newBsuid);

        findContact(senderWaId, null, tenantId).ifPresent(contact -> {
            if (newWaId != null) {
                contact.setWaId(newWaId);
            }
            if (newBsuid != null) {
                contact.setBsuid(newBsuid);
            }
            contactRepository.save(contact);
            log.info("✅ [SystemMessage] Compatibility identity migrated for contactId={}", contact.getId());
        });
    }

    private void recordUnknownSystemEvent(String systemType, JsonNode systemPayload, String senderWaId, UUID tenantId) {
        log.warn("ℹ️ [SystemMessage] Preserved unrecognized system event type='{}' sender='{}' tenantId={} payload={}",
                systemType, senderWaId, tenantId, systemPayload);
    }

    private Optional<Contact> findContact(String waId, String bsuid, UUID tenantId) {
        if (waId != null && !waId.isBlank() && tenantId != null) {
            Optional<Contact> byWaId = contactRepository.findByTenantIdAndWaId(tenantId, waId.trim());
            if (byWaId.isPresent()) return byWaId;
        }
        if (bsuid != null && !bsuid.isBlank() && tenantId != null) {
            Optional<Contact> byBsuid = contactRepository.findByTenantIdAndBsuid(tenantId, bsuid.trim());
            if (byBsuid.isPresent()) return byBsuid;
        }
        return Optional.empty();
    }
}
