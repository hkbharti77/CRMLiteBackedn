package com.chatcrmlite.backend.models.flows;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

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

    @Column(name = "flow_json", columnDefinition = "TEXT")
    private String flowJson; // Compiled Meta Flow JSON Version 3.0

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
