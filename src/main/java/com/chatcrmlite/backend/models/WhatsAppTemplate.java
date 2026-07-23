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
