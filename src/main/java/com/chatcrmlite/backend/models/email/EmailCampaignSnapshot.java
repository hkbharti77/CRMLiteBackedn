package com.chatcrmlite.backend.models.email;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "email_campaign_snapshots", indexes = {
    @Index(name = "idx_email_snapshots_tenant", columnList = "tenant_id"),
    @Index(name = "idx_email_snapshots_campaign", columnList = "campaign_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailCampaignSnapshot extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(nullable = false)
    private String subject;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    private String ctaLabel;
    private String ctaUrl;
    
    private String senderName;
    private String senderEmail;
    private String replyTo;
    
    @Column(name = "audience_type")
    private String audienceType;
    
    @Column(columnDefinition = "TEXT", name = "audience_filter_json")
    private String audienceFilterJson;
    
    @Column(columnDefinition = "TEXT", name = "template_variables_json")
    private String templateVariablesJson;

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
