package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_campaigns", indexes = {
    @Index(name = "idx_wa_camp_tenant", columnList = "tenant_id"),
    @Index(name = "idx_wa_camp_status", columnList = "status"),
    @Index(name = "idx_wa_camp_owner", columnList = "owner_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WhatsAppCampaign extends BaseTenantEntity {

    public enum Status {
        DRAFT,
        PREVIEW,
        VALIDATING,
        SCHEDULED,
        QUEUED,
        RUNNING,
        PAUSED,
        FAILED,
        CANCELLED,
        COMPLETED
    }

    public enum TargetType {
        ALL_CONTACTS,
        TAG_BASED,
        LEAD_STATUS_BASED,
        CSV_EXCEL_UPLOAD,
        CUSTOM_SEGMENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_snapshot_id")
    private WhatsAppTemplateSnapshot templateSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TargetType targetType;

    @Column(columnDefinition = "TEXT")
    private String targetFilterJson; // Tags list, lead statuses list, etc.

    @Column(columnDefinition = "TEXT")
    private String variableMappingJson; // Dynamic mapping: {"1": "contact.name", "2": "lead.dealValue"}

    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean saveImportedRecipients = false;

    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    @JsonIgnoreProperties({"password", "tenant", "ipWhitelist", "googleAccessToken", "googleRefreshToken", "hibernateLazyInitializer", "handler"})
    private User owner;

    @PrePersist
    public void prePersist() {
        super.populateTenant();
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = Status.DRAFT;
        }
        if (targetType == null) {
            targetType = TargetType.ALL_CONTACTS;
        }
    }
}
