package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenant_credit_transactions", indexes = {
    @Index(name = "idx_tenant_credit_tx_tenant_id", columnList = "tenant_id, created_at DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantCreditTransaction implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Tenant tenant;

    @Column(nullable = false)
    private Long amount; // Positive for additions/top-ups, negative for deductions

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    @Column(nullable = false, length = 50)
    private String category; // 'INCLUDED_PLAN', 'TOP_UP_PACK', 'USAGE_DEDUCTION', 'REFUND_ADJUSTMENT'

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "reference_id", length = 100)
    private String referenceId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
