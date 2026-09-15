package com.chatcrmlite.backend.models.email;

import com.chatcrmlite.backend.models.BaseTenantEntity;
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
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class EmailCampaignRecipient extends BaseTenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

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

    @Column(name = "reply_token", unique = true, nullable = false)
    private String replyToken;

    @Column(name = "last_message_id")
    private String lastMessageId;

    @Column(name = "replied_at")
    private java.time.Instant repliedAt;

    @Builder.Default
    @Column(name = "reply_count", nullable = false)
    private int replyCount = 0;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.trackingToken == null || this.trackingToken.isBlank()) {
            this.trackingToken = UUID.randomUUID().toString();
        }
        if (this.replyToken == null || this.replyToken.isBlank()) {
            byte[] randomBytes = new byte[24];
            new java.security.SecureRandom().nextBytes(randomBytes);
            this.replyToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        }
    }

    public enum DeliveryStatus {
        PENDING, SENDING, SENT, DELIVERED, BOUNCED, FAILED
    }

    public enum BounceType {
        HARD, SOFT
    }
}
