package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_campaign_variants", indexes = {
    @Index(name = "idx_wa_variants_camp", columnList = "campaign_id"),
    @Index(name = "idx_wa_variants_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WhatsAppCampaignVariant extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private WhatsAppCampaign campaign;

    @Column(name = "variant_label", length = 10, nullable = false)
    private String variantLabel; // "A", "B", "C", "D"

    @Column(name = "template_name", nullable = false)
    private String templateName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_snapshot_id")
    private WhatsAppTemplateSnapshot templateSnapshot;

    @Column(name = "variable_mapping_json", columnDefinition = "TEXT")
    private String variableMappingJson;

    @Builder.Default
    @Column(name = "sample_percentage")
    private Integer samplePercentage = 50;

    @Builder.Default
    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount = 0;

    @Builder.Default
    @Column(name = "sent_count", nullable = false)
    private Integer sentCount = 0;

    @Builder.Default
    @Column(name = "delivered_count", nullable = false)
    private Integer deliveredCount = 0;

    @Builder.Default
    @Column(name = "read_count", nullable = false)
    private Integer readCount = 0;

    @Builder.Default
    @Column(name = "click_count", nullable = false)
    private Integer clickCount = 0;

    @Builder.Default
    @Column(name = "is_winner", nullable = false)
    private Boolean isWinner = false;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.sampleCount == null) {
            this.sampleCount = 0;
        }
        if (this.sentCount == null) {
            this.sentCount = 0;
        }
        if (this.deliveredCount == null) {
            this.deliveredCount = 0;
        }
        if (this.readCount == null) {
            this.readCount = 0;
        }
        if (this.clickCount == null) {
            this.clickCount = 0;
        }
        if (this.isWinner == null) {
            this.isWinner = false;
        }
    }

    public double getReadRate() {
        if (deliveredCount != null && deliveredCount > 0) {
            return ((double) readCount / deliveredCount) * 100.0;
        } else if (sentCount != null && sentCount > 0) {
            return ((double) readCount / sentCount) * 100.0;
        }
        return 0.0;
    }

    public double getCtr() {
        if (deliveredCount != null && deliveredCount > 0) {
            return ((double) clickCount / deliveredCount) * 100.0;
        } else if (sentCount != null && sentCount > 0) {
            return ((double) clickCount / sentCount) * 100.0;
        }
        return 0.0;
    }
}
