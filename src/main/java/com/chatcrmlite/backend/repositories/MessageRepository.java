package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.dto.MessageDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    List<Message> findAllByContactOrderByTimestampAsc(Contact contact);
    List<Message> findAllByContactIn(Collection<Contact> contacts);
    Optional<Message> findByWaMessageId(String waMessageId);
    
    // For RAG Context Analysis
    List<Message> findByContactAndDirection(Contact contact, Message.Direction direction, org.springframework.data.domain.Pageable pageable);
    
    long countByContact(Contact contact);
    
    Optional<Message> findFirstByMediaIdOrderByTimestampDesc(String mediaId);

    /**
     * Fetch messages with contact eagerly - DTO conversion done in service layer.
     * Avoids LazyInitializationException when serializing to JSON.
     */
    @Query("SELECT DISTINCT m FROM Message m " +
           "JOIN FETCH m.contact c " +
           "WHERE c = :contact " +
           "ORDER BY m.timestamp ASC")
    List<Message> findAllByContactAsDTO(@Param("contact") Contact contact);
}
