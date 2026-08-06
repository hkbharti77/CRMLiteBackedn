package com.chatcrmlite.backend.services.campaign;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatcrmlite.backend.services.campaign.segment.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class BaseAudienceResolver {

    protected final ContactRepository contactRepository;
    protected final LeadRepository leadRepository;
    protected final ObjectMapper objectMapper;
    protected final TagSegmentParser tagSegmentParser;
    protected final TagSegmentValidator tagSegmentValidator;
    protected final TagSegmentMatcher tagSegmentMatcher;

    public List<Contact> resolveContacts(User owner, String targetType, String filterJson) {
        if ("ALL_CONTACTS".equals(targetType) || "ALL".equals(targetType)) {
            return contactRepository.findAllByOwner(owner);
        }

        if ("TAG_BASED".equals(targetType) || "TAGGED".equals(targetType)) {
            TagSegmentFilter filter = tagSegmentParser.parse(filterJson);
            tagSegmentValidator.validate(filter);

            List<Contact> ownerContacts = contactRepository.findAllByOwnerWithTags(owner);
            List<Contact> filtered = new ArrayList<>();
            
            for (Contact c : ownerContacts) {
                if (tagSegmentMatcher.matches(c, filter)) {
                    filtered.add(c);
                }
            }
            return filtered;
        }

        if ("LEAD_STATUS_BASED".equals(targetType)) {
            List<String> statuses = parseStringsFromJson(filterJson, "leadStatuses");
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

        return new ArrayList<>();
    }

    protected List<UUID> parseIdsFromJson(String json, String key) {
        if (json == null || json.isBlank() || !json.startsWith("{")) return Collections.emptyList();
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

    protected List<String> parseStringsFromJson(String json, String key) {
        if (json == null || json.isBlank() || !json.startsWith("{")) return Collections.emptyList();
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
