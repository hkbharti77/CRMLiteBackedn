package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

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
            if (profileName != null && !profileName.isBlank() && isFallbackName(c.getName(), c.getWaId(), c.getPhone())) {
                log.info("[ContactResolution] Auto-updating contact name from '{}' to '{}' for waId={}", c.getName(), profileName, waId);
                c.setName(profileName);
                contactRepository.save(c);
            }
            return c;
        }
        
        Contact newContact = Contact.builder()
                .waId(waId)
                .name(profileName != null && !profileName.isBlank() ? profileName : "WhatsApp User " + waId)
                .source("WhatsApp")
                .owner(owner)
                .build();
        try {
            return contactRepository.save(newContact);
        } catch (DataIntegrityViolationException e) {
            log.warn("Race condition during contact creation for waId {}, fetching existing...", waId);
            return contactRepository.findByWaIdAndOwner(waId, owner)
                    .orElseThrow(() -> new RuntimeException("Contact creation failed and not found: " + waId));
        }
    }

    private boolean isFallbackName(String currentName, String waId, String phone) {
        if (currentName == null || currentName.isBlank()) {
            return true;
        }
        String lowerName = currentName.trim().toLowerCase();
        if (lowerName.startsWith("whatsapp user") || lowerName.startsWith("test user") || lowerName.equals("csv recipient")) {
            return true;
        }
        // Check if the current name is essentially just the phone number
        String cleanName = currentName.replaceAll("[^0-9+]", "");
        if (!cleanName.isBlank()) {
            if (waId != null && waId.contains(cleanName)) return true;
            if (phone != null && phone.contains(cleanName)) return true;
        }
        return false;
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
