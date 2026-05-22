package com.chatcrmlite.backend.events.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ConversationEventStore {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public void append(ConversationEvent event) {
        try {
            EventEntity entity = new EventEntity();
            entity.setConversationId(event.getConversationId());
            entity.setVersion(event.getVersion());
            entity.setEventType(event.getClass().getSimpleName());
            entity.setPayload(objectMapper.writeValueAsString(event));
            
            entityManager.persist(entity);
            entityManager.flush();
        } catch (Exception e) {
            throw new RuntimeException("Failed to append event to store. Check for version conflicts.", e);
        }
    }

    public List<ConversationEvent> getEvents(UUID conversationId) {
        List<EventEntity> entities = entityManager.createQuery(
            "SELECT e FROM EventEntity e WHERE e.conversationId = :cid ORDER BY e.version ASC", EventEntity.class)
            .setParameter("cid", conversationId)
            .getResultList();

        return entities.stream().map(this::deserialize).collect(Collectors.toList());
    }

    private ConversationEvent deserialize(EventEntity entity) {
        try {
            return objectMapper.readValue(entity.getPayload(), ConversationEvent.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize event", e);
        }
    }

    @Entity
    @Table(name = "conversation_events", 
           uniqueConstraints = @UniqueConstraint(columnNames = {"conversationId", "version"}))
    @Getter @Setter
    public static class EventEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        
        @Column(nullable = false)
        private UUID conversationId;
        
        @Column(nullable = false)
        private int version;
        
        @Column(nullable = false)
        private String eventType;
        
        @Column(columnDefinition = "jsonb", nullable = false)
        private String payload;
    }
}
