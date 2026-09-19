package com.chatcrmlite.backend.models.flows;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.lang.Nullable;

@Entity
@Table(name = "flow_revisions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_flow_version", columnNames = {"flow_id", "version_number"})
}, indexes = {
    @Index(name = "idx_rev_flow", columnList = "flow_id"),
    @Index(name = "idx_rev_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class FlowRevision extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id", nullable = false)
    @JsonIgnore
    private WhatsAppFlow flow;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "fields_config_json", nullable = false, columnDefinition = "TEXT")
    private String fieldsConfigJson; // Internal CRM Component Abstraction

    // ── Meta container identity (per-revision) ──────────────────────────────────

    /**
     * Unique name sent to Meta when creating the Flow container for this revision.
     * Format: "<displayName[0..40]>--r-<revisionId[0..16] hex>"
     * This is NOT the user-facing display name. It is used for timeout reconciliation.
     */
    @Column(name = "meta_name", length = 150)
    private String metaName;

    /** Meta Flow container ID assigned to this specific revision. */
    @Column(name = "meta_flow_id")
    private String metaFlowId;

    // ── Revision lifecycle ───────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "revision_status", nullable = false)
    @Builder.Default
    private FlowRevisionStatus revisionStatus = FlowRevisionStatus.DRAFT;

    // ── Meta validation / health (revision-scoped) ───────────────────────────────

    /** Serialized {@code List<FlowValidationError>} from the Meta asset upload response. */
    @Column(name = "validation_errors_json", columnDefinition = "TEXT")
    private String validationErrorsJson;

    /** Meta-reported flow status after GET /{flow-id}: DRAFT, PUBLISHED, DEPRECATED, THROTTLED, BLOCKED */
    @Column(name = "meta_status", length = 50)
    private String metaStatus;

    /** Full structured JSON from Meta's health_status field (preserved for diagnostics). */
    @Column(name = "meta_health_json", columnDefinition = "TEXT")
    private String metaHealthJson;

    /** Convenience flag extracted from meta_health_json.can_send_message */
    @Column(name = "meta_can_send_message")
    private Boolean metaCanSendMessage;

    // ── Deprecation tracking ────────────────────────────────────────────────────

    @Column(name = "deprecated_at")
    private LocalDateTime deprecatedAt;

    /** null | DEPRECATION_PENDING | DEPRECATED */
    @Column(name = "deprecation_status", length = 50)
    private String deprecationStatus;

    @Column(name = "deprecation_attempts")
    @Builder.Default
    private int deprecationAttempts = 0;

    @Column(name = "last_deprecation_error", length = 1000)
    private String lastDeprecationError;

    // ── Flow JSON ───────────────────────────────────────────────────────────────

    @Column(name = "flow_json", columnDefinition = "TEXT")
    private String flowJson; // Compiled Meta Flow JSON (Version 7.0)

    @Column(name = "confirmation_message", length = 1000)
    private String confirmationMessage; // Revision-scoped confirmation copy

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RevisionStatus status = RevisionStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    @JsonIgnore
    private User createdBy;

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
