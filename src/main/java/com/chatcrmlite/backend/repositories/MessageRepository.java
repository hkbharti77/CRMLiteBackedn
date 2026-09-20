package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.dto.MessageDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    List<Message> findAllByContactOrderByTimestampAsc(Contact contact);
    List<Message> findAllByContactIn(Collection<Contact> contacts);
    Optional<Message> findByWaMessageId(String waMessageId);
    
    // For Conversation Memory (Recent Turns & Time Window)
    List<Message> findTop50ByContactOrderByTimestampDesc(Contact contact);
    List<Message> findByContactAndTimestampAfterOrderByTimestampAsc(Contact contact, java.time.LocalDateTime timestamp);
    
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

    @Query("SELECT MAX(m.timestamp) FROM Message m " +
           "WHERE m.contact.tenant.id = :tenantId " +
           "AND m.contact.waId = :customerWaId " +
           "AND m.direction = com.chatcrmlite.backend.models.Message.Direction.INCOMING")
    Optional<java.time.LocalDateTime> findLatestInboundTimestamp(
        @Param("tenantId") UUID tenantId,
        @Param("customerWaId") String customerWaId
    );

    @Modifying
    @Query("UPDATE Message m SET m.deliveryStatus = com.chatcrmlite.backend.models.Message.DeliveryStatus.READ " +
           "WHERE m.waMessageId = :waMessageId " +
           "AND (m.deliveryStatus IN (com.chatcrmlite.backend.models.Message.DeliveryStatus.SENT, " +
           "                          com.chatcrmlite.backend.models.Message.DeliveryStatus.DELIVERED) " +
           "     OR m.deliveryStatus IS NULL)")
    int markReadConditional(@Param("waMessageId") String waMessageId);

    @Modifying
    @Query("UPDATE Message m SET m.deliveryStatus = com.chatcrmlite.backend.models.Message.DeliveryStatus.DELIVERED " +
           "WHERE m.waMessageId = :waMessageId " +
           "AND (m.deliveryStatus = com.chatcrmlite.backend.models.Message.DeliveryStatus.SENT " +
           "     OR m.deliveryStatus IS NULL)")
    int markDeliveredConditional(@Param("waMessageId") String waMessageId);

    @Modifying
    @Query("UPDATE Message m SET m.deliveryStatus = com.chatcrmlite.backend.models.Message.DeliveryStatus.FAILED " +
           "WHERE m.waMessageId = :waMessageId " +
           "AND (m.deliveryStatus IN (com.chatcrmlite.backend.models.Message.DeliveryStatus.SENT, " +
           "                          com.chatcrmlite.backend.models.Message.DeliveryStatus.DELIVERED) " +
           "     OR m.deliveryStatus IS NULL)")
    int markFailedConditional(@Param("waMessageId") String waMessageId);
}
