package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignAudienceResolver {

    private static final Pattern E164_PHONE_PATTERN = Pattern.compile("^\\+?[1-9]\\d{1,14}$");

    private final ContactRepository contactRepository;
    private final LeadRepository leadRepository;
    private final WhatsAppCampaignRecipientRepository recipientRepository;
    private final WhatsAppCampaignAnalyticsRepository analyticsRepository;
    private final PersonalizationEngine personalizationEngine;
    private final ObjectMapper objectMapper;

    @Transactional
    public void resolveAndFreezeAudience(WhatsAppCampaign campaign) {
        log.info("[AudienceResolver] Resolving immutable audience snapshot for campaignId={} targetType={}", campaign.getId(), campaign.getTargetType());

        List<TransientRecipient> targetRecipients = fetchTargetRecipients(campaign);
        int totalTargeted = targetRecipients.size();

        int validCount = 0;
        int skippedCount = 0;

        List<WhatsAppCampaignRecipient> recipientsToSave = new ArrayList<>();
        Set<String> processedPhones = new HashSet<>();

        for (TransientRecipient tr : targetRecipients) {
            String phone = tr.getPhone();
            if (phone == null || phone.isBlank() || !isValidPhoneNumber(phone)) {
                recordSkippedRecipient(campaign, tr.getContact(), phone, "INVALID_PHONE");
                skippedCount++;
                continue;
            }

            // Normalize phone number to E.164
            String normalizedPhone = phone.startsWith("+") ? phone : "+" + phone.replaceAll("[^0-9]", "");

            // Deduplicate in this campaign
            if (processedPhones.contains(normalizedPhone)) {
                skippedCount++;
                continue;
            }
            processedPhones.add(normalizedPhone);

            // Check uniqueness in DB (only if contact is present and persisted)
            if (tr.getContact() != null && tr.getContact().getId() != null) {
                if (recipientRepository.existsByCampaignIdAndContactId(campaign.getId(), tr.getContact().getId())) {
                    skippedCount++;
                    continue;
                }
            } else {
                // For transient contacts, check by phone number instead
                // (though the DB unique constraint on (campaign_id, phone_number) will also enforce this)
            }

            Contact contact = tr.getContact();

            // Opt-out / DND validation
            if (contact != null && (Boolean.TRUE.equals(contact.getOptedOut()) || Boolean.TRUE.equals(contact.getBlacklisted()))) {
                recordSkippedRecipient(campaign, contact, normalizedPhone, "OPTED_OUT_OR_BLACK_LISTED");
                skippedCount++;
                continue;
            }

            // Fetch primary lead for parameter resolution if available (only for persisted contacts)
            Lead lead = null;
            if (contact != null && contact.getId() != null) {
                lead = leadRepository.findTopByContactOrderByCreatedAtDesc(contact).orElse(null);
            }

            // Pre-render parameter values JSON. We pass the contact (which might be transient with name/email).
            List<String> renderedParams = personalizationEngine.renderTemplateParameters(
                    campaign.getVariableMappingJson(),
                    contact,
                    lead,
                    campaign.getOwner()
            );

            String paramsJson = "";
            try {
                paramsJson = objectMapper.writeValueAsString(renderedParams);
            } catch (Exception e) {
                log.warn("[AudienceResolver] Failed to serialize parameters for phone={}", normalizedPhone);
            }

            WhatsAppCampaignRecipient recipient = WhatsAppCampaignRecipient.builder()
                    .campaign(campaign)
                    .contact(contact != null && contact.getId() != null ? contact : null) // Only link if it's a persisted contact
                    .phoneNumber(normalizedPhone)
                    .resolvedVariablesJson(paramsJson)
                    .status(WhatsAppCampaignRecipient.RecipientStatus.PENDING)
                    .retryCount(0)
                    .build();

            recipient.setTenant(campaign.getTenant());
            recipientsToSave.add(recipient);
            validCount++;
        }

        recipientRepository.saveAll(recipientsToSave);

        // Update Campaign Analytics
        WhatsAppCampaignAnalytics analytics = analyticsRepository.findByCampaign(campaign)
                .orElseGet(() -> WhatsAppCampaignAnalytics.builder()
                        .campaign(campaign)
                        .build());

        analytics.setTenant(campaign.getTenant());
        analytics.setTotalTargetRecipients(totalTargeted);
        analytics.setTotalValidRecipients(validCount);
        analytics.setTotalSkippedRecipients(skippedCount);
        analytics.setTotalQueued(validCount);
        analytics.setLastUpdatedAt(LocalDateTime.now());
        analyticsRepository.save(analytics);

        log.info("[AudienceResolver] Frozen audience snapshot for campaignId={}: Targeted={}, Valid={}, Skipped={}",
                campaign.getId(), totalTargeted, validCount, skippedCount);
    }

    private List<TransientRecipient> fetchTargetRecipients(WhatsAppCampaign campaign) {
        User owner = campaign.getOwner();
        List<TransientRecipient> result = new ArrayList<>();

        if (campaign.getTargetType() == WhatsAppCampaign.TargetType.ALL_CONTACTS) {
            for (Contact c : contactRepository.findAllByOwner(owner)) {
                result.add(new TransientRecipient(c, c.getWaId() != null ? c.getWaId() : c.getPhone()));
            }
            return result;
        }

        if (campaign.getTargetType() == WhatsAppCampaign.TargetType.TAG_BASED) {
            List<UUID> tagIds = parseIdsFromJson(campaign.getTargetFilterJson(), "tagIds");
            List<String> tagNames = parseStringsFromJson(campaign.getTargetFilterJson(), "tagNames");
            
            List<Contact> ownerContacts = contactRepository.findAllByOwnerWithTags(owner);
            if (tagIds.isEmpty() && tagNames.isEmpty()) {
                for (Contact c : ownerContacts) {
                    result.add(new TransientRecipient(c, c.getWaId() != null ? c.getWaId() : c.getPhone()));
                }
                return result;
            }

            for (Contact c : ownerContacts) {
                if (c.getTags() != null) {
                    boolean matchesTag = c.getTags().stream().anyMatch(t -> 
                        (tagIds.contains(t.getId())) || 
                        (t.getName() != null && tagNames.stream().anyMatch(tn -> tn.equalsIgnoreCase(t.getName())))
                    );
                    if (matchesTag) {
                        result.add(new TransientRecipient(c, c.getWaId() != null ? c.getWaId() : c.getPhone()));
                    }
                }
            }
            return result;
        }

        if (campaign.getTargetType() == WhatsAppCampaign.TargetType.LEAD_STATUS_BASED) {
            List<String> statuses = parseStringsFromJson(campaign.getTargetFilterJson(), "leadStatuses");
            if (statuses.isEmpty()) {
                for (Contact c : contactRepository.findAllByOwner(owner)) {
                    result.add(new TransientRecipient(c, c.getWaId() != null ? c.getWaId() : c.getPhone()));
                }
                return result;
            }
            List<Lead.LeadStatus> leadStatuses = new ArrayList<>();
            for (String s : statuses) {
                try {
                    leadStatuses.add(Lead.LeadStatus.valueOf(s.trim().toUpperCase()));
                } catch (Exception ignored) {}
            }
            List<Lead> leads = leadRepository.findByOwnerAndStatusIn(owner, leadStatuses);
            Set<UUID> addedContacts = new HashSet<>();
            for (Lead l : leads) {
                if (l.getContact() != null && !addedContacts.contains(l.getContact().getId())) {
                    addedContacts.add(l.getContact().getId());
                    result.add(new TransientRecipient(l.getContact(), l.getContact().getWaId() != null ? l.getContact().getWaId() : l.getContact().getPhone()));
                }
            }
            return result;
        }

        if (campaign.getTargetType() == WhatsAppCampaign.TargetType.CSV_EXCEL_UPLOAD) {
            boolean saveImported = Boolean.TRUE.equals(campaign.getSaveImportedRecipients());
            
            try {
                if (campaign.getTargetFilterJson() != null && !campaign.getTargetFilterJson().isBlank()) {
                    Map<String, Object> map = objectMapper.readValue(campaign.getTargetFilterJson(), new TypeReference<Map<String, Object>>() {});
                    List<?> list = (List<?>) map.get("csvRecipients");

                    // Read the phone column name (defaults to legacy "phone" / "phoneNumber")
                    String phoneColumn = map.get("phoneColumn") != null ? map.get("phoneColumn").toString() : null;

                    // Read applied filters and match logic (AND / OR)
                    List<?> appliedFilters = (List<?>) map.get("appliedFilters");
                    String filterMatchLogic = map.get("filterMatchLogic") != null ? map.get("filterMatchLogic").toString().toUpperCase() : "AND";

                    if (list != null) {
                        for (Object o : list) {
                            if (o instanceof Map) {
                                Map<?, ?> item = (Map<?, ?>) o;

                                // Defensively lowercase map keys to handle Name, name, NAME seamlessly
                                Map<String, String> lowerKeysItem = new HashMap<>();
                                for (Map.Entry<?, ?> entry : item.entrySet()) {
                                    if (entry.getKey() != null && entry.getValue() != null) {
                                        lowerKeysItem.put(entry.getKey().toString().toLowerCase().trim(), entry.getValue().toString().trim());
                                    }
                                }

                                // Apply column filters using AND / OR logic
                                if (appliedFilters != null && !appliedFilters.isEmpty()) {
                                    boolean isOrLogic = "OR".equalsIgnoreCase(filterMatchLogic);
                                    boolean passesFilter = isOrLogic ? false : true;

                                    for (Object filterObj : appliedFilters) {
                                        if (filterObj instanceof Map) {
                                            Map<?, ?> filter = (Map<?, ?>) filterObj;
                                            String filterColumn = filter.get("column") != null ? filter.get("column").toString().toLowerCase().trim() : null;
                                            String filterOperator = filter.get("operator") != null ? filter.get("operator").toString() : "EQUALS";
                                            String filterValue = filter.get("value") != null ? filter.get("value").toString() : "";

                                            if (filterColumn != null && !filterValue.isBlank()) {
                                                String cellValue = lowerKeysItem.getOrDefault(filterColumn, "");
                                                boolean match = matchesFilter(cellValue, filterOperator, filterValue);
                                                if (isOrLogic) {
                                                    if (match) {
                                                        passesFilter = true;
                                                        break;
                                                    }
                                                } else {
                                                    if (!match) {
                                                        passesFilter = false;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (!passesFilter) {
                                        continue;
                                    }
                                }

                                // Resolve phone number
                                String phone = null;
                                if (phoneColumn != null) {
                                    phone = lowerKeysItem.get(phoneColumn.toLowerCase().trim());
                                }
                                if (phone == null || phone.isBlank()) {
                                    phone = lowerKeysItem.get("phone");
                                    if (phone == null || phone.isBlank()) {
                                        phone = lowerKeysItem.get("phonenumber");
                                    }
                                }

                                if (phone != null && !phone.isBlank()) {
                                    String cleanPhone = phone.startsWith("+") ? phone : "+" + phone.replaceAll("[^0-9]", "");
                                    
                                    String name = lowerKeysItem.get("name");
                                    String email = lowerKeysItem.get("email");

                                    if (saveImported) {
                                        // Save or Update contact without overwriting existing manual names
                                        Contact contact = contactRepository.findByWaIdAndOwner(cleanPhone, owner)
                                                .orElseGet(() -> {
                                                    Contact c = Contact.builder()
                                                            .waId(cleanPhone)
                                                            .name(name)
                                                            .email(email)
                                                            .owner(owner)
                                                            .build();
                                                    c.setTenant(owner.getTenant());
                                                    return c;
                                                });
                                        
                                        // If contact already existed, we can fill blank fields safely
                                        if (contact.getId() != null) {
                                            boolean updated = false;
                                            if (name != null && !name.isBlank() && (contact.getName() == null || contact.getName().isBlank())) {
                                                contact.setName(name);
                                                updated = true;
                                            }
                                            if (email != null && !email.isBlank() && (contact.getEmail() == null || contact.getEmail().isBlank())) {
                                                contact.setEmail(email);
                                                updated = true;
                                            }
                                            if (updated) {
                                                contact = contactRepository.save(contact);
                                            }
                                        } else {
                                            // New contact
                                            contact = contactRepository.save(contact);
                                        }
                                        result.add(new TransientRecipient(contact, cleanPhone));
                                    } else {
                                        // Do not save to DB. Build a transient contact just for personalization rendering.
                                        Contact transientContact = Contact.builder()
                                                .waId(cleanPhone)
                                                .name(name)
                                                .email(email)
                                                .owner(owner)
                                                .build();
                                        result.add(new TransientRecipient(transientContact, cleanPhone));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[AudienceResolver] Error parsing CSV recipients for campaignId={}: {}", campaign.getId(), e.getMessage());
            }
            return result;
        }

        // Fallback
        for (Contact c : contactRepository.findAllByOwner(owner)) {
            result.add(new TransientRecipient(c, c.getWaId() != null ? c.getWaId() : c.getPhone()));
        }
        return result;
    }

    /**
     * Evaluates whether a cell value matches the given filter operator and value.
     */
    private boolean matchesFilter(String cellValue, String operator, String filterValue) {
        if (cellValue == null) cellValue = "";
        String cellLower = cellValue.toLowerCase().trim();
        String filterLower = filterValue.toLowerCase().trim();

        return switch (operator.toUpperCase()) {
            case "EQUALS" -> cellLower.equals(filterLower);
            case "NOT_EQUALS" -> !cellLower.equals(filterLower);
            case "CONTAINS" -> cellLower.contains(filterLower);
            case "STARTS_WITH" -> cellLower.startsWith(filterLower);
            case "IN" -> {
                String[] values = filterLower.split(",");
                boolean matched = false;
                for (String v : values) {
                    if (cellLower.equals(v.trim())) {
                        matched = true;
                        break;
                    }
                }
                yield matched;
            }
            default -> cellLower.equals(filterLower);
        };
    }

    private boolean isValidPhoneNumber(String phone) {
        String cleaned = phone.replaceAll("[\\s\\-\\(\\)]", "");
        return E164_PHONE_PATTERN.matcher(cleaned).matches();
    }

    private void recordSkippedRecipient(WhatsAppCampaign campaign, Contact contact, String phone, String reason) {
        WhatsAppCampaignRecipient recipient = WhatsAppCampaignRecipient.builder()
                .campaign(campaign)
                .contact(contact != null && contact.getId() != null ? contact : null)
                .phoneNumber(phone != null ? phone : "UNKNOWN")
                .status(WhatsAppCampaignRecipient.RecipientStatus.SKIPPED)
                .skipReason(reason)
                .build();
        recipient.setTenant(campaign.getTenant());
        recipientRepository.save(recipient);
    }

    private List<UUID> parseIdsFromJson(String json, String key) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            List<?> list = (List<?>) map.get(key);
            if (list == null) return Collections.emptyList();
            List<UUID> ids = new ArrayList<>();
            for (Object item : list) {
                ids.add(UUID.fromString(item.toString()));
            }
            return ids;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<String> parseStringsFromJson(String json, String key) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            List<?> list = (List<?>) map.get(key);
            if (list == null) return Collections.emptyList();
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(item.toString());
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class TransientRecipient {
        private Contact contact;
        private String phone;
    }
}
