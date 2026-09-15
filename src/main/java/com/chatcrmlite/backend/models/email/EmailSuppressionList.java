package com.chatcrmlite.backend.models.email;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "email_suppression_list", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"tenant_id", "email"})
})
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class EmailSuppressionList extends BaseTenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SuppressionReason reason;

    @Column(name = "source_campaign_id")
    private UUID sourceCampaignId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "created_by")
    private UUID createdBy;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public enum SuppressionReason {
        UNSUBSCRIBED, HARD_BOUNCE, SOFT_BOUNCE, COMPLAINT, MANUAL, INVALID
    }
}
