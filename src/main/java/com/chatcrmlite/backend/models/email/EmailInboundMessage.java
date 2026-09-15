package com.chatcrmlite.backend.models.email;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_inbound_messages", uniqueConstraints = {
    @UniqueConstraint(name = "uk_inbound_provider_msg", columnNames = {"provider", "provider_message_id"})
})
@AssociationOverride(name = "tenant", joinColumns = @JoinColumn(name = "tenant_id", nullable = true))
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class EmailInboundMessage extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "recipient_id")
    private UUID recipientId;

    @Column(name = "campaign_recipient_id")
    private UUID campaignRecipientId;

    @Column(name = "reply_token", length = 64)
    private String replyToken;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_message_id", nullable = false)
    private String providerMessageId;

    @Column(name = "message_id")
    private String messageId;

    @Column(name = "in_reply_to")
    private String inReplyTo;

    @Column(name = "references_header", columnDefinition = "TEXT")
    private String referencesHeader;

    @Column(name = "from_email", nullable = false)
    private String fromEmail;

    @Column(name = "to_email", nullable = false)
    private String toEmail;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(name = "text_body", columnDefinition = "TEXT")
    private String textBody;

    @Column(name = "html_body", columnDefinition = "TEXT")
    private String htmlBody;

    @Column(name = "reply_snippet", length = 500)
    private String replySnippet;

    @Enumerated(EnumType.STRING)
    @Column(name = "attribution_status", nullable = false)
    @Builder.Default
    private AttributionStatus attributionStatus = AttributionStatus.ATTRIBUTED;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.receivedAt == null) {
            this.receivedAt = Instant.now();
        }
    }

    public enum AttributionStatus {
        ATTRIBUTED, UNATTRIBUTED
    }
}
