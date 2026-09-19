package com.chatcrmlite.backend.models.flows;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.Contact;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persists the correlation context for a WhatsApp Flow message sent to a customer.
 *
 * <p>Written <em>before</em> the WhatsApp API call (status=CREATED) so that
 * SEND_FAILED can be tracked even when the API throws. Updated to SENT after the
 * API confirms delivery. Looked up by {@code flow_token} when the customer's
 * {@code nfm_reply} arrives, enabling exact flow+revision attribution.
 *
 * <pre>
 *   CREATED → SENT → RESPONDED
 *           → SEND_FAILED
 *   CREATED/SENT → EXPIRED (TTL sweeper)
 * </pre>
 */
@Entity
@Table(
    name = "flow_send_sessions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_fss_flow_token", columnNames = {"flow_token"})
    },
    indexes = {
        @Index(name = "idx_fss_tenant",    columnList = "tenant_id"),
        @Index(name = "idx_fss_token",     columnList = "flow_token"),
        @Index(name = "idx_fss_contact",   columnList = "contact_id"),
        @Index(name = "idx_fss_status_exp",columnList = "status, expires_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class FlowSendSession extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Opaque token generated at send time and included in the Flow message payload.
     * Extracted from {@code nfm_reply.response_json.flow_token} on webhook ingress.
     */
    @Column(name = "flow_token", nullable = false, unique = true)
    private String flowToken;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id")
    @JsonIgnore
    private WhatsAppFlow flow;

    /**
     * The exact revision that was active when this Flow message was sent.
     * This is the source of truth for submission attribution — even if a newer revision
     * is published before the customer responds, the submission is linked to THIS revision.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revision_id")
    @JsonIgnore
    private FlowRevision revision;

    /** Meta Flow container ID for the revision at send time. */
    @Column(name = "meta_flow_id")
    private String metaFlowId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    @JsonIgnoreProperties({"tags", "owner", "assignedAgent"})
    private Contact contact;

    /** WhatsApp message ID returned by the API after successful delivery; null until SENT. */
    @Column(name = "message_id")
    private String messageId;

    /** Optional campaign reference for analytics. */
    @Column(name = "campaign_id")
    private String campaignId;

    /** Session TTL — typically now + 72h. After this, no nfm_reply is expected. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private FlowSendSessionStatus status = FlowSendSessionStatus.CREATED;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    @Override
    protected void populateTenant() {
        super.populateTenant();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
