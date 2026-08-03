package com.chatcrmlite.backend.models.email;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "email_campaign_audit_logs", indexes = {
    @Index(name = "idx_email_audit_tenant", columnList = "tenant_id"),
    @Index(name = "idx_email_audit_campaign", columnList = "campaign_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailCampaignAuditLog extends BaseTenantEntity {

    public enum Action {
        CREATED,
        SCHEDULED,
        STARTED,
        PAUSED,
        RESUMED,
        CANCELLED,
        COMPLETED,
        FAILED,
        TEST_SENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private CustomEmail campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actorUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    @Column(columnDefinition = "TEXT")
    private String detailsJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
