package com.chatcrmlite.backend.models.email;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.CustomEmail;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "email_campaign_variants")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class EmailCampaignVariant extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private CustomEmail campaign;

    @Column(name = "variant_code", nullable = false, length = 5)
    private String variantCode; // 'A', 'B'

    @Column(nullable = false)
    private String name; // e.g. "Variant A (Control)"

    @Column(nullable = false)
    private String subject;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    @Column(name = "builder_json", columnDefinition = "TEXT")
    private String builderJson;

    @Builder.Default
    @Column(name = "sample_count", nullable = false)
    private int sampleCount = 0;

    @Builder.Default
    @Column(name = "sent_count", nullable = false)
    private int sentCount = 0;

    @Builder.Default
    @Column(name = "delivered_count", nullable = false)
    private int deliveredCount = 0;

    @Builder.Default
    @Column(name = "opened_count", nullable = false)
    private int openedCount = 0;

    @Builder.Default
    @Column(name = "clicked_count", nullable = false)
    private int clickedCount = 0;

    @Builder.Default
    @Column(name = "is_winner", nullable = false)
    private boolean isWinner = false;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public double getOpenRate() {
        if (sentCount == 0 && deliveredCount == 0) return 0.0;
        int base = deliveredCount > 0 ? deliveredCount : sentCount;
        return ((double) openedCount / base) * 100.0;
    }

    public double getCtr() {
        if (sentCount == 0 && deliveredCount == 0) return 0.0;
        int base = deliveredCount > 0 ? deliveredCount : sentCount;
        return ((double) clickedCount / base) * 100.0;
    }

    public double getClickToOpenRate() {
        if (openedCount == 0) return 0.0;
        return ((double) clickedCount / openedCount) * 100.0;
    }
}
