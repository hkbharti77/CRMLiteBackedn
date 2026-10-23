package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_campaign_tracked_links", indexes = {
    @Index(name = "idx_wa_tracked_links_token", columnList = "token", unique = true),
    @Index(name = "idx_wa_tracked_links_camp", columnList = "campaign_id"),
    @Index(name = "idx_wa_tracked_links_recip", columnList = "recipient_id"),
    @Index(name = "idx_wa_tracked_links_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WhatsAppCampaignTrackedLink extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private WhatsAppCampaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private WhatsAppCampaignRecipient recipient;

    @Column(name = "token", unique = true, nullable = false, length = 32)
    private String token;

    @Column(name = "original_url", columnDefinition = "TEXT", nullable = false)
    private String originalUrl;

    @Builder.Default
    @Column(name = "button_index")
    private Integer buttonIndex = 0;

    @Column(name = "button_text", length = 100)
    private String buttonText;

    @Builder.Default
    @Column(name = "click_count", nullable = false)
    private Integer clickCount = 0;

    @Column(name = "first_clicked_at")
    private LocalDateTime firstClickedAt;

    @Column(name = "last_clicked_at")
    private LocalDateTime lastClickedAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.clickCount == null) {
            this.clickCount = 0;
        }
    }
}
