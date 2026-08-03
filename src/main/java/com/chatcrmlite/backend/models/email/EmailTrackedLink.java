package com.chatcrmlite.backend.models.email;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "email_tracked_link")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailTrackedLink {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "link_token", unique = true, nullable = false)
    private String linkToken;

    @Column(name = "destination_url", columnDefinition = "TEXT", nullable = false)
    private String destinationUrl;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
