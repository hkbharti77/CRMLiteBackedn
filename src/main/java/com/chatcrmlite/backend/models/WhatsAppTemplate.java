package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_templates", indexes = {
    @Index(name = "idx_wa_temp_owner", columnList = "owner_id"),
    @Index(name = "idx_wa_temp_name", columnList = "name"),
    @Index(name = "idx_wa_temp_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppTemplate extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String language; // e.g., "en_US", "hi"

    @Column(nullable = false)
    private String category; // "MARKETING", "UTILITY", "AUTHENTICATION"

    @Column(nullable = false)
    private String status; // "APPROVED", "PENDING", "REJECTED", "PAUSED", "DISABLED"

    private String headerType; // "NONE", "TEXT", "IMAGE", "VIDEO", "DOCUMENT"

    @Column(columnDefinition = "TEXT")
    private String headerContent;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String bodyText;

    @Column(columnDefinition = "TEXT")
    private String footerText;

    @Column(columnDefinition = "TEXT")
    private String buttonsJson; // JSON array of buttons (QUICK_REPLY, URL, PHONE_NUMBER)

    private String metaTemplateId;

    @Column(name = "rejected_reason")
    private String rejectedReason;

    @Column(name = "quality_rating", length = 20)
    @Builder.Default
    private String qualityRating = "UNKNOWN";

    @Column(name = "quality_updated_at")
    private java.time.Instant qualityUpdatedAt;

    @Column(name = "detected_correct_category", length = 50)
    private String detectedCorrectCategory;

    @Column(name = "category_correction_status", length = 50)
    private String categoryCorrectionStatus;

    @Column(name = "category_correction_detected_at")
    private java.time.Instant categoryCorrectionDetectedAt;

    @Column(name = "category_correction_reason")
    private String categoryCorrectionReason;

    @Column(name = "category_change_effective_at")
    private java.time.Instant categoryChangeEffectiveAt;

    @Column(name = "category_previous_value", length = 50)
    private String categoryPreviousValue;

    @Column(name = "category_change_reason")
    private String categoryChangeReason;

    @Column(name = "last_status_event_at")
    private java.time.Instant lastStatusEventAt;

    @Column(name = "last_quality_event_at")
    private java.time.Instant lastQualityEventAt;

    @Column(name = "last_category_event_at")
    private java.time.Instant lastCategoryEventAt;

    @Column(name = "last_component_event_at")
    private java.time.Instant lastComponentEventAt;

    @Column(name = "last_component_sync_at")
    private java.time.Instant lastComponentSyncAt;

    @Column(name = "last_component_sync_status", length = 50)
    private String lastComponentSyncStatus;

    @Column(name = "last_component_sync_error", columnDefinition = "TEXT")
    private String lastComponentSyncError;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @PrePersist
    @PreUpdate
    public void sanitizeFields() {
        if (name != null) {
            name = name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        }
        if (language == null || language.isBlank()) {
            language = "en_US";
        }
        if (category == null || category.isBlank()) {
            category = "MARKETING";
        }
        if (status == null || status.isBlank()) {
            status = "PENDING";
        }
    }
}
