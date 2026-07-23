package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_campaign_audit_logs", indexes = {
    @Index(name = "idx_wa_cal_tenant", columnList = "tenant_id"),
    @Index(name = "idx_wa_cal_camp", columnList = "campaign_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppCampaignAuditLog extends BaseTenantEntity {

    public enum Action {
        CAMPAIGN_CREATED,
        PREVIEW_GENERATED,
        TEST_SENT,
        SCHEDULED,
        STARTED,
        PAUSED,
        RESUMED,
        CANCELLED,
        COMPLETED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private WhatsAppCampaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actorUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    @Column(columnDefinition = "TEXT")
    private String detailsJson;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
