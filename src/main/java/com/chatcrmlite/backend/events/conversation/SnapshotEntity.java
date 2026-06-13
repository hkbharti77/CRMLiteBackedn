package com.chatcrmlite.backend.events.conversation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "conversation_snapshots")
@Getter
@Setter
public class SnapshotEntity {
    @Id
    private UUID conversationId;
    
    @Column(nullable = false)
    private int version;
    
    @Column(columnDefinition = "jsonb", nullable = false)
    private String state;
    
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
