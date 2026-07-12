package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stores per-tenant configuration for bulk lead upload validation.
 * One row per tenant. Does NOT extend BaseTenantEntity — tenant_id is stored
 * as a plain UUID column to avoid Hibernate filter complications.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bulk_upload_validation_configs")
public class BulkUploadValidationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    /**
     * Comma-separated list of extra required fields beyond the defaults,
     * e.g. "source,status".
     */
    @Column(name = "extra_fields", nullable = false, columnDefinition = "TEXT")
    private String extraFields;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
