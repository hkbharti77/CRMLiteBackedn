package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppUserPreferenceLedger;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppUserPreferenceLedgerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppPreferenceService {

    private final WhatsAppUserPreferenceLedgerRepository preferenceLedgerRepository;
    private final ContactRepository contactRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void handleUserPreferences(JsonNode entry, JsonNode change, Instant fallbackTimestamp) {
        if (change == null) return;
        JsonNode value = change.path("value");
        if (value.isMissingNode() || value.isNull()) return;

        String wabaId = entry.path("id").asText(null);
        String phoneNumberId = value.path("metadata").path("phone_number_id").asText(null);

        UUID tenantId = null;
        if (phoneNumberId != null && !phoneNumberId.isBlank()) {
            tenantId = whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId.trim()).orElse(null);
        }
        if (tenantId == null && wabaId != null && !wabaId.isBlank()) {
            tenantId = whatsappConfigRepository.findTenantIdByWabaId(wabaId.trim()).orElse(null);
        }

        if (tenantId == null) {
            log.warn("⚠️ [UserPreferences] No tenant matched for wabaId={} phoneNumberId={}", wabaId, phoneNumberId);
            return;
        }

        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return;

        // Meta can send preferences under value.user_preferences array or single object
        JsonNode userPreferencesNode = value.path("user_preferences");
        List<JsonNode> prefList = new ArrayList<>();
        if (userPreferencesNode.isArray()) {
            userPreferencesNode.forEach(prefList::add);
        } else if (!userPreferencesNode.isMissingNode() && !userPreferencesNode.isNull()) {
            prefList.add(userPreferencesNode);
        } else {
            // Also check root value if directly carrying category and value
            if (value.has("category") && value.has("value")) {
                prefList.add(value);
            }
        }

        for (JsonNode prefNode : prefList) {
            processSinglePreference(tenant, wabaId, phoneNumberId, prefNode, value, fallbackTimestamp);
        }
    }

    private void processSinglePreference(Tenant tenant, String wabaId, String phoneNumberId, JsonNode prefNode, JsonNode rawValue, Instant fallbackTimestamp) {
        String category = prefNode.path("category").asText("");
        String preferenceValue = prefNode.path("value").asText("");

        long rawSec = prefNode.path("timestamp").asLong(0);
        if (rawSec == 0) {
            rawSec = fallbackTimestamp != null ? fallbackTimestamp.getEpochSecond() : (System.currentTimeMillis() / 1000);
        }
        Instant eventInstant = Instant.ofEpochSecond(rawSec);

        // Identify target user / contact
        String userId = prefNode.path("user_id").asText(null);
        if (userId == null || userId.isBlank()) {
            userId = rawValue.path("user_id").asText(null);
        }
        String parentUserId = prefNode.path("parent_user_id").asText(null);
        if (parentUserId == null || parentUserId.isBlank()) {
            parentUserId = rawValue.path("parent_user_id").asText(null);
        }
        String waId = prefNode.path("wa_id").asText(null);
        if (waId == null || waId.isBlank()) {
            waId = rawValue.path("wa_id").asText(null);
        }
        if (waId == null || waId.isBlank()) {
            waId = rawValue.path("from").asText(null);
        }

        // Canonical sorted JSON fingerprint
        String canonicalJson = canonicalizeJson(prefNode);
        String fingerprint = generateSha256(tenant.getId() + ":" + wabaId + ":" + phoneNumberId + ":" + canonicalJson);

        if (preferenceLedgerRepository.existsByTenantIdAndCanonicalFingerprint(tenant.getId(), fingerprint)) {
            log.info("ℹ️ [UserPreferences] Deduplicated duplicate preference event fingerprint={}", fingerprint);
            return;
        }

        // Record in ledger
        WhatsAppUserPreferenceLedger ledger = WhatsAppUserPreferenceLedger.builder()
                .wabaId(wabaId)
                .phoneNumberId(phoneNumberId)
                .userId(userId != null && !userId.isBlank() ? userId.trim() : null)
                .parentUserId(parentUserId != null && !parentUserId.isBlank() ? parentUserId.trim() : null)
                .waId(waId != null && !waId.isBlank() ? waId.trim() : null)
                .category(category)
                .preferenceValue(preferenceValue)
                .eventTimestamp(eventInstant)
                .canonicalFingerprint(fingerprint)
                .rawPayloadJson(prefNode.toString())
                .build();
        ledger.setTenant(tenant);
        preferenceLedgerRepository.save(ledger);

        log.info("📝 [UserPreferences] Logged preference category='{}' value='{}' for user='{}' waId='{}' tenantId={}",
                category, preferenceValue, userId, waId, tenant.getId());

        // Forward-compatible category handling:
        // Only alter marketing suppression if category == "marketing_messages"
        if ("marketing_messages".equalsIgnoreCase(category.trim())) {
            updateMarketingSuppression(tenant.getId(), waId, userId, preferenceValue, eventInstant);
        } else {
            log.info("ℹ️ [UserPreferences] Unhandled preference category='{}' preserved in ledger without altering suppression", category);
        }
    }

    private void updateMarketingSuppression(UUID tenantId, String waId, String userId, String preferenceValue, Instant eventInstant) {
        Optional<Contact> optContact = findContact(tenantId, waId, userId);
        if (optContact.isEmpty()) {
            log.warn("⚠️ [UserPreferences] Contact not found for tenantId={} waId='{}' userId='{}' to update marketing preference (quarantined)",
                    tenantId, waId, userId);
            return;
        }

        Contact contact = optContact.get();

        // Sequence / Out-of-order check using Instant
        if (contact.getMarketingPreferenceAt() != null && eventInstant.isBefore(contact.getMarketingPreferenceAt())) {
            log.warn("⚠️ [UserPreferences] Stale preference event ignored (currentAt={} > eventAt={}) for contactId={}",
                    contact.getMarketingPreferenceAt(), eventInstant, contact.getId());
            return;
        }

        if ("stop".equalsIgnoreCase(preferenceValue.trim())) {
            contact.setMarketingOptedOut(true);
            contact.setMarketingOptedOutAt(eventInstant);
            contact.setMarketingOptOutSource("WHATSAPP_NATIVE_PREFERENCE");
            contact.setMarketingPreferenceAt(eventInstant);
            contactRepository.save(contact);
            log.info("🛑 [UserPreferences] ContactId={} marketing suppression ACTIVATED (opted out)", contact.getId());
        } else if ("resume".equalsIgnoreCase(preferenceValue.trim())) {
            contact.setMarketingOptedOut(false);
            contact.setMarketingOptedOutAt(null);
            contact.setMarketingOptOutSource("WHATSAPP_NATIVE_PREFERENCE_RESUME");
            contact.setMarketingPreferenceAt(eventInstant);
            contactRepository.save(contact);
            log.info("🟢 [UserPreferences] ContactId={} marketing suppression CLEARED (opted in)", contact.getId());
        } else {
            log.info("ℹ️ [UserPreferences] Unrecognized preference value='{}' for marketing_messages", preferenceValue);
        }
    }

    private Optional<Contact> findContact(UUID tenantId, String waId, String userId) {
        // Priority 1: BSUID identity if present
        if (userId != null && !userId.isBlank()) {
            Optional<Contact> c = contactRepository.findByTenantIdAndBsuid(tenantId, userId.trim());
            if (c.isPresent()) return c;
            // Also check waId if userId happens to be phone number
            c = contactRepository.findByTenantIdAndWaId(tenantId, userId.trim());
            if (c.isPresent()) return c;
        }
        // Priority 2: Phone identity (waId)
        if (waId != null && !waId.isBlank()) {
            Optional<Contact> c = contactRepository.findByTenantIdAndWaId(tenantId, waId.trim());
            if (c.isPresent()) return c;
        }
        // Priority 3: Unresolved / quarantine
        return Optional.empty();
    }

    public String canonicalizeJson(JsonNode node) {
        try {
            ObjectMapper sortedMapper = objectMapper.copy();
            sortedMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            Object obj = sortedMapper.treeToValue(node, Object.class);
            return sortedMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return node != null ? node.toString() : "";
        }
    }

    public static String generateSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
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
