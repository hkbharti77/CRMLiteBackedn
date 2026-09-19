package com.chatcrmlite.backend.models.payments;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationStatus;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.utils.EncryptionConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_payment_configs", uniqueConstraints = {
    @UniqueConstraint(name = "uk_tenant_integration", columnNames = {"tenant_id", "integration_type"}),
    @UniqueConstraint(name = "uk_tenant_config_composite", columnNames = {"id", "tenant_id"})
}, indexes = {
    @Index(name = "idx_pay_config_tenant", columnList = "tenant_id"),
    @Index(name = "idx_pay_config_hash", columnList = "webhook_key_hash")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TenantPaymentConfig extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "integration_type", nullable = false, length = 50)
    private PaymentIntegrationType integrationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private PaymentIntegrationStatus status = PaymentIntegrationStatus.ACTIVE;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "webhook_key_hash", nullable = false, unique = true, length = 64)
    private String webhookKeyHash;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "whatsapp_account_id")
    private WhatsAppConfig whatsappAccount;

    @Column(name = "meta_payment_configuration_name", length = 100)
    private String metaPaymentConfigurationName;

    @Convert(converter = EncryptionConverter.class)
    @Column(name = "encrypted_key_id", columnDefinition = "TEXT")
    private String keyId;

    @Convert(converter = EncryptionConverter.class)
    @Column(name = "encrypted_key_secret", columnDefinition = "TEXT")
    private String keySecret;

    @Convert(converter = EncryptionConverter.class)
    @Column(name = "encrypted_webhook_secret", columnDefinition = "TEXT")
    private String webhookSecret;

    @Column(name = "currency", length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
