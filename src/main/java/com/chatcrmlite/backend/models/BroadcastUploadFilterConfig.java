package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-tenant configuration for broadcast CSV/Excel upload column filters.
 * The admin defines which columns from uploaded files are available as audience filters.
 *
 * <p>Does NOT extend BaseTenantEntity — tenant_id is stored as a plain UUID column
 * (same pattern as {@link BulkUploadValidationConfig}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "broadcast_upload_filter_configs")
public class BroadcastUploadFilterConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    /**
     * JSON array of column names that are available as filter criteria.
     * e.g. ["city","plan","source","region"]
     */
    @Column(name = "filter_columns_json", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String filterColumnsJson = "[]";

    /**
     * JSON array of filter rule definitions with operators.
     * e.g. [{"column":"city","operator":"EQUALS","label":"City"},{"column":"plan","operator":"IN","label":"Plan Type"}]
     */
    @Column(name = "filter_rules_json", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String filterRulesJson = "[]";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
