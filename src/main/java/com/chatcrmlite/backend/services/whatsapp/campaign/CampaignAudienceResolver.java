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

        List<Contact> targetContacts = fetchTargetContacts(campaign);
        int totalTargeted = targetContacts.size();

        int validCount = 0;
        int skippedCount = 0;

        List<WhatsAppCampaignRecipient> recipientsToSave = new ArrayList<>();

        for (Contact contact : targetContacts) {
            // Check uniqueness
            if (recipientRepository.existsByCampaignIdAndContactId(campaign.getId(), contact.getId())) {
                skippedCount++;
                continue;
            }

            // 1. Phone validation
            String phone = contact.getWaId() != null ? contact.getWaId() : contact.getPhone();
            if (phone == null || phone.isBlank() || !isValidPhoneNumber(phone)) {
                recordSkippedRecipient(campaign, contact, phone, "INVALID_PHONE");
                skippedCount++;
                continue;
            }

            // Normalize phone number
            String normalizedPhone = phone.startsWith("+") ? phone : "+" + phone.replaceAll("[^0-9]", "");

            // 2. Opt-out / DND validation
            if (Boolean.TRUE.equals(contact.getOptedOut()) || Boolean.TRUE.equals(contact.getBlacklisted())) {
                recordSkippedRecipient(campaign, contact, normalizedPhone, "OPTED_OUT_OR_BLACK_LISTED");
                skippedCount++;
                continue;
            }

            // Fetch primary lead for parameter resolution if available
            Lead lead = leadRepository.findTopByContactOrderByCreatedAtDesc(contact).orElse(null);

            // Pre-render parameter values JSON
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
                log.warn("[AudienceResolver] Failed to serialize parameters for contact={}", contact.getId());
            }

            WhatsAppCampaignRecipient recipient = WhatsAppCampaignRecipient.builder()
                    .campaign(campaign)
                    .contact(contact)
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

    private List<Contact> fetchTargetContacts(WhatsAppCampaign campaign) {
        User owner = campaign.getOwner();
        if (campaign.getTargetType() == WhatsAppCampaign.TargetType.ALL_CONTACTS) {
            return contactRepository.findAllByOwner(owner);
        }

        if (campaign.getTargetType() == WhatsAppCampaign.TargetType.TAG_BASED) {
            List<UUID> tagIds = parseIdsFromJson(campaign.getTargetFilterJson(), "tagIds");
            List<String> tagNames = parseStringsFromJson(campaign.getTargetFilterJson(), "tagNames");
            
            List<Contact> ownerContacts = contactRepository.findAllByOwnerWithTags(owner);
            if (tagIds.isEmpty() && tagNames.isEmpty()) {
                return ownerContacts;
            }

            List<Contact> filtered = new ArrayList<>();
            for (Contact c : ownerContacts) {
                if (c.getTags() != null) {
                    boolean matchesTag = c.getTags().stream().anyMatch(t -> 
                        (tagIds.contains(t.getId())) || 
                        (t.getName() != null && tagNames.stream().anyMatch(tn -> tn.equalsIgnoreCase(t.getName())))
                    );
                    if (matchesTag) {
                        filtered.add(c);
                    }
                }
            }
            return filtered;
        }

        if (campaign.getTargetType() == WhatsAppCampaign.TargetType.LEAD_STATUS_BASED) {
            List<String> statuses = parseStringsFromJson(campaign.getTargetFilterJson(), "leadStatuses");
            if (statuses.isEmpty()) {
                return contactRepository.findAllByOwner(owner);
            }
            List<Lead.LeadStatus> leadStatuses = new ArrayList<>();
            for (String s : statuses) {
                try {
                    leadStatuses.add(Lead.LeadStatus.valueOf(s.trim().toUpperCase()));
                } catch (Exception ignored) {}
            }
            List<Lead> leads = leadRepository.findByOwnerAndStatusIn(owner, leadStatuses);
            List<Contact> contacts = new ArrayList<>();
            for (Lead l : leads) {
                if (l.getContact() != null && !contacts.contains(l.getContact())) {
                    contacts.add(l.getContact());
                }
            }
            return contacts;
        }

        if (campaign.getTargetType() == WhatsAppCampaign.TargetType.CSV_EXCEL_UPLOAD) {
            List<Contact> csvContacts = new ArrayList<>();
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

                                // Apply column filters using AND / OR logic
                                if (appliedFilters != null && !appliedFilters.isEmpty()) {
                                    boolean isOrLogic = "OR".equalsIgnoreCase(filterMatchLogic);
                                    boolean passesFilter = isOrLogic ? false : true;

                                    for (Object filterObj : appliedFilters) {
                                        if (filterObj instanceof Map) {
                                            Map<?, ?> filter = (Map<?, ?>) filterObj;
                                            String filterColumn = filter.get("column") != null ? filter.get("column").toString() : null;
                                            String filterOperator = filter.get("operator") != null ? filter.get("operator").toString() : "EQUALS";
                                            String filterValue = filter.get("value") != null ? filter.get("value").toString() : "";

                                            if (filterColumn != null && !filterValue.isBlank()) {
                                                String cellValue = item.get(filterColumn) != null ? item.get(filterColumn).toString() : "";
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

                                // Resolve phone number — try dynamic phoneColumn first, then fallbacks
                                String phone = null;
                                if (phoneColumn != null && item.get(phoneColumn) != null) {
                                    phone = item.get(phoneColumn).toString();
                                }
                                if (phone == null || phone.isBlank()) {
                                    phone = item.get("phone") != null ? item.get("phone").toString() :
                                           item.get("phoneNumber") != null ? item.get("phoneNumber").toString() : null;
                                }

                                if (phone != null && !phone.isBlank()) {
                                    String cleanPhone = phone.startsWith("+") ? phone : "+" + phone.replaceAll("[^0-9]", "");
                                    String name = item.get("name") != null ? item.get("name").toString() : "CSV Recipient";
                                    String email = item.get("email") != null ? item.get("email").toString() : null;

                                    Contact contact = contactRepository.findByWaIdAndOwner(cleanPhone, owner)
                                            .orElseGet(() -> {
                                                Contact c = Contact.builder()
                                                        .waId(cleanPhone)
                                                        .name(name)
                                                        .email(email)
                                                        .owner(owner)
                                                        .build();
                                                c.setTenant(owner.getTenant());
                                                return contactRepository.save(c);
                                            });
                                    if (!csvContacts.contains(contact)) {
                                        csvContacts.add(contact);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[AudienceResolver] Error parsing CSV recipients for campaignId={}: {}", campaign.getId(), e.getMessage());
            }
            return csvContacts;
        }

        return contactRepository.findAllByOwner(owner);
    }

    /**
     * Evaluates whether a cell value matches the given filter operator and value.
     * Supports: EQUALS, CONTAINS, STARTS_WITH, NOT_EQUALS, IN (comma-separated list).
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
                .contact(contact)
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
}
