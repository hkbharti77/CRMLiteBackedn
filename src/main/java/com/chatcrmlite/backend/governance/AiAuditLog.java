package com.chatcrmlite.backend.governance;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_audit_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AiAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID conversationId;

    @Column(columnDefinition = "text")
    private String rawPrompt;

    @Column(columnDefinition = "text")
    private String redactedPrompt;

    @Column(columnDefinition = "text")
    private String aiResponse;

    private String modelName;
    private long latencyMs;
    private double confidenceScore;

    @Column(columnDefinition = "jsonb")
    private String decisionTrace; // e.g. {"sources": ["doc_123", "kb_456"], "reasoning": "..."}

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    
    private LocalDateTime expiresAt; // For GDPR automated deletion
}

@Repository
interface AiAuditRepository extends JpaRepository<AiAuditLog, UUID> {}
