package com.chatcrmlite.backend.models.email;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "email_campaign_recipient", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"tenant_id", "campaign_id", "email"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailCampaignRecipient {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(nullable = false)
    private String email;

    @Column(name = "tracking_token", unique = true, nullable = false)
    private String trackingToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false)
    private DeliveryStatus deliveryStatus;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "first_opened_at")
    private LocalDateTime firstOpenedAt;

    @Column(name = "first_clicked_at")
    private LocalDateTime firstClickedAt;

    @Column(name = "unsubscribed_at")
    private LocalDateTime unsubscribedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "bounce_type")
    private BounceType bounceType;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.trackingToken == null || this.trackingToken.isBlank()) {
            this.trackingToken = UUID.randomUUID().toString();
        }
    }

    public enum DeliveryStatus {
        PENDING, SENT, DELIVERED, BOUNCED, FAILED
    }

    public enum BounceType {
        HARD, SOFT
    }
}
