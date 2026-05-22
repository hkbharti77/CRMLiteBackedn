package com.chatcrmlite.backend.events.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ConversationSnapshotStore {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public void save(ConversationAggregate aggregate) {
        SnapshotEntity entity = new SnapshotEntity();
        entity.setConversationId(aggregate.getId());
        entity.setVersion(aggregate.getVersion());
        try {
            entity.setState(objectMapper.writeValueAsString(aggregate));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize snapshot", e);
        }
        
        entityManager.merge(entity);
    }

    public Optional<ConversationAggregate> load(UUID conversationId) {
        SnapshotEntity entity = entityManager.find(SnapshotEntity.class, conversationId);
        if (entity == null) return Optional.empty();

        try {
            return Optional.of(objectMapper.readValue(entity.getState(), ConversationAggregate.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize snapshot", e);
        }
    }

    @Entity
    @Table(name = "conversation_snapshots")
    @Getter @Setter
    public static class SnapshotEntity {
        @Id
        private UUID conversationId;
        
        @Column(nullable = false)
        private int version;
        
        @Column(columnDefinition = "jsonb", nullable = false)
        private String state;
        
        private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now();
    }
}
