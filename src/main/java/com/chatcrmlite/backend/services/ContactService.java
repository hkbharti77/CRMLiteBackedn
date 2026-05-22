package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.ContactDTO;
import com.chatcrmlite.backend.dto.MessageDTO;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tag;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ContactService {

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private TagService tagService;

    /**
     * Get all contacts for a user as DTOs.
     * Eagerly loads tags to prevent LazyInitializationException.
     */
    @Transactional(readOnly = true)
    public List<ContactDTO> getContactsByUser(User user) {
        List<Contact> contacts = contactRepository.findAllByOwnerWithTags(user);
        return contacts.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * Get a single contact by ID as DTO.
     * Eagerly loads tags to prevent LazyInitializationException.
     */
    @Transactional(readOnly = true)
    public ContactDTO getContactById(UUID contactId, User owner) {
        Contact contact = contactRepository.findByIdWithTags(contactId)
                .filter(c -> c.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        return toDTO(contact);
    }

    public List<MessageDTO> getChatMessages(UUID contactId, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        
        return messageRepository.findAllByContactOrderByTimestampAsc(contact).stream()
                .map(msg -> MessageDTO.builder()
                        .id(msg.getId())
                        .content(msg.getContent())
                        .direction(msg.getDirection())
                        .timestamp(msg.getTimestamp())
                        .waMessageId(msg.getWaMessageId())
                        .build())
                .collect(Collectors.toList());
    }

    public Contact saveContact(Contact contact) {
        return contactRepository.save(contact);
    }

    @Transactional
    public void updateTags(UUID contactId, List<String> tagNames, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        
        List<com.chatcrmlite.backend.models.Tag> resolvedTags = 
                tagService.getOrCreateTags(tagNames, com.chatcrmlite.backend.models.Tag.TYPE_CONTACT, owner);
        
        contact.setTags(resolvedTags);
        contactRepository.save(contact);
    }
    
    /**
     * Convert Contact entity to DTO.
     * Must be called within a transaction where tags are already loaded.
     */
    private ContactDTO toDTO(Contact c) {
        return ContactDTO.builder()
                .id(c.getId())
                .waId(c.getWaId())
                .name(c.getName())
                .tags(c.getTags().stream()
                        .map(Tag::getName)
                        .collect(Collectors.toList()))
                .source(c.getSource())
                .build();
    }
}
