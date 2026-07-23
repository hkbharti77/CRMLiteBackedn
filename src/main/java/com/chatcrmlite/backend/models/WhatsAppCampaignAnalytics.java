package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_campaign_analytics", indexes = {
    @Index(name = "idx_wa_ca_tenant", columnList = "tenant_id"),
    @Index(name = "idx_wa_ca_camp", columnList = "campaign_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppCampaignAnalytics extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false, unique = true)
    private WhatsAppCampaign campaign;

    @Builder.Default
    private Integer totalTargetRecipients = 0;

    @Builder.Default
    private Integer totalValidRecipients = 0;

    @Builder.Default
    private Integer totalSkippedRecipients = 0;

    @Builder.Default
    private Integer totalQueued = 0;

    @Builder.Default
    private Integer totalSent = 0;

    @Builder.Default
    private Integer totalDelivered = 0;

    @Builder.Default
    private Integer totalRead = 0;

    @Builder.Default
    private Integer totalFailed = 0;

    @Builder.Default
    private Integer totalRetried = 0;

    @Builder.Default
    private Integer trackedLinkClicks = 0;

    private LocalDateTime lastUpdatedAt;

    @PrePersist
    @PreUpdate
    public void prePersist() {
        super.populateTenant();
        lastUpdatedAt = LocalDateTime.now();
    }
}
