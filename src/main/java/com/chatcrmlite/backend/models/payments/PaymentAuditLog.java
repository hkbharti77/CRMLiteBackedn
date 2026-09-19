package com.chatcrmlite.backend.models.payments;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "payment_audit_logs", indexes = {
    @Index(name = "idx_audit_order", columnList = "tenant_id, order_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PaymentAuditLog extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "actor_type", nullable = false, length = 50)
    private String actorType; // USER, SYSTEM, WEBHOOK, AI_AGENT, RECONCILER

    @Column(name = "actor_id", length = 100)
    private String actorId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "old_status", length = 50)
    private String oldStatus;

    @Column(name = "new_status", length = 50)
    private String newStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
