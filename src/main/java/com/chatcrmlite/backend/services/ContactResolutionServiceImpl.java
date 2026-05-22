package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
public class ContactResolutionServiceImpl implements ContactResolutionService {

    @Autowired
    private ContactRepository contactRepository;

    @Override
    public Contact resolveContact(String waId, String profileName, User owner) {
        Optional<Contact> existing = contactRepository.findByWaIdAndOwner(waId, owner);
        if (existing.isPresent()) {
            Contact c = existing.get();
            if (profileName != null && (c.getName() == null || c.getName().startsWith("WhatsApp User"))) {
                c.setName(profileName);
                contactRepository.save(c);
            }
            return c;
        }
        
        Contact newContact = Contact.builder()
                .waId(waId)
                .name(profileName != null ? profileName : "WhatsApp User " + waId)
                .source("WhatsApp")
                .owner(owner)
                .build();
        return contactRepository.save(newContact);
    }

    @Override
    public String extractProfileName(JsonNode contactsNode, String from) {
        if (contactsNode != null && contactsNode.isArray()) {
            for (JsonNode c : contactsNode) {
                if (from.equals(c.path("wa_id").asText())) {
                    String name = c.path("profile").path("name").asText();
                    if (name != null && !name.isBlank()) return name;
                }
            }
        }
        return null;
    }
}
