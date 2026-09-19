package com.chatcrmlite.backend.models.payments;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.models.enums.PaymentWebhookStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_webhook_events", uniqueConstraints = {
    @UniqueConstraint(name = "uk_integration_event", columnNames = {"payment_integration_id", "provider_event_key"})
}, indexes = {
    @Index(name = "idx_webhook_status", columnList = "processing_status, provider")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PaymentWebhookEvent extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_integration_id", nullable = false)
    private TenantPaymentConfig paymentIntegration;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 50)
    private PaymentIntegrationType provider;

    @Column(name = "provider_event_key", nullable = false, length = 200)
    private String providerEventKey;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "signature_valid", nullable = false)
    @Builder.Default
    private Boolean signatureValid = false;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb", nullable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 50)
    @Builder.Default
    private PaymentWebhookStatus processingStatus = PaymentWebhookStatus.RECEIVED;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
