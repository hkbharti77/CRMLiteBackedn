package com.chatcrmlite.backend.models.email;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "email_tracked_link")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class EmailTrackedLink extends BaseTenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "link_token", unique = true, nullable = false)
    private String linkToken;

    @Column(name = "destination_url", columnDefinition = "TEXT", nullable = false)
    private String destinationUrl;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
