package com.chatcrmlite.backend.models.flows;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_generation_audit_logs", indexes = {
    @Index(name = "idx_ai_audit_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiGenerationAuditLog extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    @Override
    protected void populateTenant() {
        super.populateTenant();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
