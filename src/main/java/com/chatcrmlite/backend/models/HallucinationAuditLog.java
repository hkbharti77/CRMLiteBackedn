package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "hallucination_audit_logs", indexes = {
    @Index(name = "idx_hallucination_audit_tenant_date", columnList = "tenant_id, created_at DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HallucinationAuditLog implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @Column(columnDefinition = "text")
    private String query;

    @Column(columnDefinition = "text")
    private String response;

    @Column(name = "context_sample", columnDefinition = "text")
    private String contextSample;

    @Column(nullable = false, length = 50)
    private String result;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
