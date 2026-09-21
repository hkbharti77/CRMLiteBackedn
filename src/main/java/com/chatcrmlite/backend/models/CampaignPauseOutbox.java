package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "campaign_pause_outbox", uniqueConstraints = {
    @UniqueConstraint(name = "uq_campaign_pause_event", columnNames = {"tenant_id", "template_id", "source_event_id"})
}, indexes = {
    @Index(name = "idx_campaign_pause_outbox_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignPauseOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private WhatsAppTemplate template;

    @Column(name = "source_event_id", nullable = false)
    private String sourceEventId;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;
}
