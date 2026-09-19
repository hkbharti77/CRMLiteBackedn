package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_action_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "message_id")
    private String messageId;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType; // "SEND_CATALOG", "CLARIFY", "NONE"

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "catalog_id")
    private TenantAiCatalog catalog;

    @Column(length = 100)
    private String model;

    @Column(name = "decision_source", nullable = false, length = 50)
    private String decisionSource; // "NATIVE_TOOL", "STRUCTURED_FALLBACK", "TEST_SIMULATION"

    @Column(name = "raw_action", columnDefinition = "TEXT")
    private String rawAction;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String caption;

    @Column(name = "validation_stage", length = 50)
    private String validationStage; // "PASSED", "TENANT_MISMATCH", "INACTIVE", "COOLDOWN", "IDEMPOTENCY_DUPLICATE", "NOT_ELIGIBLE"

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "delivery_status", length = 50)
    @Builder.Default
    private String deliveryStatus = "PENDING"; // "PENDING", "SENT", "DELIVERED", "READ", "FAILED"

    @Column(nullable = false)
    private boolean validated;

    @Column(nullable = false)
    private boolean executed;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Builder.Default
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
