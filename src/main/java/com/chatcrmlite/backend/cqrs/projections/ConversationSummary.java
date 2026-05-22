package com.chatcrmlite.backend.cqrs.projections;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "read_conversation_summaries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConversationSummary {
    @Id
    private UUID conversationId;
    private UUID tenantId;
    private String contactWaId;
    private String flowType;
    private String currentState;
    private LocalDateTime lastUpdatedAt;
    private String lastMessagePreview;
}
