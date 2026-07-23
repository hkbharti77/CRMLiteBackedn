package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_campaign_executions", indexes = {
    @Index(name = "idx_wa_ce_tenant", columnList = "tenant_id"),
    @Index(name = "idx_wa_ce_camp", columnList = "campaign_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppCampaignExecution extends BaseTenantEntity {

    public enum ExecutionType {
        DRY_RUN,
        FULL_BROADCAST,
        RETRY_FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private WhatsAppCampaign campaign;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionType executionType;

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;

    private Integer batchSize;
    private Integer messagingRatePerSec;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }
}
