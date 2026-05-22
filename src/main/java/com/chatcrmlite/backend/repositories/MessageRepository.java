package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    List<Message> findAllByContactOrderByTimestampAsc(Contact contact);
    List<Message> findAllByContactIn(Collection<Contact> contacts);
    Optional<Message> findByWaMessageId(String waMessageId);
    
    // For RAG Context Analysis
    List<Message> findByContactAndDirection(Contact contact, Message.Direction direction, org.springframework.data.domain.Pageable pageable);
    
    long countByContact(Contact contact);
}
