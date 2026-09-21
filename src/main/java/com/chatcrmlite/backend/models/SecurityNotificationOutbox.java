package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "security_notification_outbox", uniqueConstraints = {
    @UniqueConstraint(name = "uq_security_notification_event", columnNames = {"tenant_id", "event_type", "phone_number_id", "source_event_id"})
}, indexes = {
    @Index(name = "idx_security_outbox_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityNotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "phone_number_id", length = 100)
    private String phoneNumberId;

    @Column(name = "meta_user_id", length = 100)
    private String metaUserId;

    @Column(name = "source_event_id", nullable = false)
    private String sourceEventId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public UUID getTenantId() {
        return tenant != null ? tenant.getId() : null;
    }

    public String getDetails() {
        return description;
    }
}
