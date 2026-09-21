package com.chatcrmlite.backend.models.flows;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_flows", indexes = {
    @Index(name = "idx_flow_tenant", columnList = "tenant_id"),
    @Index(name = "idx_flow_meta_id", columnList = "meta_flow_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WhatsAppFlow extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** @deprecated Each revision now owns its Meta container ID via {@link FlowRevision#metaFlowId}.
     * This field is kept for backward compatibility during migration.
     * Use {@code activeMetaFlowId} for routing to the currently live Meta container. */
    @Deprecated
    @Column(name = "meta_flow_id")
    private String metaFlowId;

    /** UUID of the currently active (published) FlowRevision. Set atomically on publish success. */
    @Column(name = "active_revision_id")
    private UUID activeRevisionId;

    /** Meta Flow container ID for the currently active published revision. Used for routing. */
    @Column(name = "active_meta_flow_id")
    private String activeMetaFlowId;

    @Column(name = "waba_id", nullable = false)
    private String wabaId; // Scoped strictly to tenant's WABA

    @Column(name = "phone_number_id", nullable = false)
    private String phoneNumberId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FlowCategory category = FlowCategory.LEAD_GENERATION;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FlowLifecycleStatus status = FlowLifecycleStatus.DRAFT;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "published_revision_id")
    @JsonIgnoreProperties({"flow", "createdBy"})
    private FlowRevision publishedRevision;

    @OneToMany(mappedBy = "flow", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("versionNumber DESC")
    @JsonIgnore
    @Builder.Default
    private List<FlowRevision> revisions = new ArrayList<>();

    @Column(name = "last_sync_error", length = 2000)
    private String lastSyncError;

    /** Meta-reported operational state: DRAFT, PUBLISHED, DEPRECATED, BLOCKED, THROTTLED */
    @Column(name = "meta_status", length = 50)
    private String metaStatus;

    /** Reason given by Meta during FLOW_STATUS_CHANGE (e.g. policy violation) */
    @Column(name = "last_meta_event_reason", length = 1000)
    private String lastMetaEventReason;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    @Override
    protected void populateTenant() {
        super.populateTenant();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
