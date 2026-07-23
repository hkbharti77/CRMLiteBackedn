package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_template_snapshots", indexes = {
    @Index(name = "idx_wa_ts_tenant", columnList = "tenant_id"),
    @Index(name = "idx_wa_ts_orig", columnList = "original_template_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppTemplateSnapshot extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID originalTemplateId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String language;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String status;

    private Integer version;

    private String headerType;

    @Column(columnDefinition = "TEXT")
    private String headerContent;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String bodyText;

    @Column(columnDefinition = "TEXT")
    private String footerText;

    @Column(columnDefinition = "TEXT")
    private String buttonsJson;

    @Column(columnDefinition = "TEXT")
    private String variablesJson; // JSON array of required variable names e.g. ["contact.name", "lead.dealValue"]

    private String metaTemplateId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (version == null) {
            version = 1;
        }
    }
}
