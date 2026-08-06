package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.services.campaign.BaseAudienceResolver;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import com.chatcrmlite.backend.services.campaign.segment.TagSegmentMatcher;
import com.chatcrmlite.backend.services.campaign.segment.TagSegmentParser;
import com.chatcrmlite.backend.services.campaign.segment.TagSegmentValidator;

@Service
public class EmailAudienceResolver extends BaseAudienceResolver {

    public EmailAudienceResolver(ContactRepository contactRepository, LeadRepository leadRepository, ObjectMapper objectMapper, TagSegmentParser tagSegmentParser, TagSegmentValidator tagSegmentValidator, TagSegmentMatcher tagSegmentMatcher) {
        super(contactRepository, leadRepository, objectMapper, tagSegmentParser, tagSegmentValidator, tagSegmentMatcher);
    }

    public List<String> resolveEmailAddresses(User owner, String targetType, String filterJson) {
        if ("MANUAL".equals(targetType)) {
            List<String> emails = new ArrayList<>();
            if (filterJson != null && !filterJson.isBlank()) {
                String[] split = filterJson.split(",");
                for (String s : split) {
                    if (!s.trim().isBlank()) {
                        emails.add(s.trim());
                    }
                }
            }
            return emails;
        }

        List<Contact> contacts = resolveContacts(owner, targetType, filterJson);
        List<String> emails = new ArrayList<>();
        
        for (Contact c : contacts) {
            if (c.getEmail() != null && !c.getEmail().isBlank()) {
                String email = c.getEmail().trim().toLowerCase();
                if (!emails.contains(email)) {
                    emails.add(email);
                }
            }
        }
        
        return emails;
    }
}
