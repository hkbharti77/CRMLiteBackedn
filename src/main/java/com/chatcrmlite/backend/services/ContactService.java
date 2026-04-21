package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.MessageDTO;
import com.chatcrmlite.backend.models.Contact;
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

    public List<Contact> getContactsByUser(User user) {
        return contactRepository.findAllByOwner(user);
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
    public void updateTags(UUID contactId, List<String> tags, User owner) {
        Contact contact = contactRepository.findById(contactId)
                .filter(c -> c.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        contact.setTags(tags);
        contactRepository.save(contact);
    }
}
