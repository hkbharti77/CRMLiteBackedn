package com.chatcrmlite.backend.models.google;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Bidirectional ID mapping between CRM resources and their Google counterparts.
 * This is the idempotency layer — prevents duplicate Google resources on retries.
 *
 * Unique constraints:
 * - (connectionId, resourceType, crmResourceId)    → one CRM resource maps to at most one Google resource
 * - (connectionId, resourceType, googleResourceId) → one Google resource maps to at most one CRM resource
 *
 * Deletion policy: Google-deleted resources set syncStatus=UNLINKED.
 * CRM records are NEVER auto-deleted when a Google resource is removed.
 */
@Entity
@Table(
    name = "google_syncs",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_gsync_crm_resource",
            columnNames = {"connection_id", "resource_type", "crm_resource_id"}
        ),
        @UniqueConstraint(
            name = "uq_gsync_google_resource",
            columnNames = {"connection_id", "resource_type", "google_resource_id"}
        )
    },
    indexes = {
        @Index(name = "idx_gsync_connection_id", columnList = "connection_id"),
        @Index(name = "idx_gsync_crm_resource_id", columnList = "crm_resource_id")
    }
)
public class GoogleSync {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** FK → GoogleConnection */
    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    /**
     * Type of resource being synced.
     * Values: CALENDAR_EVENT, TASK, CONTACT, DRIVE_FILE, SHEET
     */
    @Column(name = "resource_type", nullable = false, length = 30)
    private String resourceType;

    /** UUID of the CRM entity (Appointment, Reminder, Contact, etc.) */
    @Column(name = "crm_resource_id", nullable = false)
    private UUID crmResourceId;

    /** Google's string ID for this resource (event ID, task ID, resourceName, etc.) */
    @Column(name = "google_resource_id", length = 500)
    private String googleResourceId;

    /**
     * Direction of synchronization.
     * Values: CRM_TO_GOOGLE | GOOGLE_TO_CRM | BIDIRECTIONAL
     */
    @Column(name = "sync_direction", length = 20)
    private String syncDirection;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    /**
     * Current sync state.
     * Values: OK | ERROR | PENDING | UNLINKED | DISCONNECTED
     * - UNLINKED: Google resource was deleted; CRM record preserved.
     * - DISCONNECTED: Integration was disconnected; history preserved.
     */
    @Column(name = "sync_status", length = 20)
    private String syncStatus;

    /** Error message if syncStatus == ERROR */
    @Column(name = "sync_error", columnDefinition = "TEXT")
    private String syncError;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public UUID getCrmResourceId() { return crmResourceId; }
    public void setCrmResourceId(UUID crmResourceId) { this.crmResourceId = crmResourceId; }

    public String getGoogleResourceId() { return googleResourceId; }
    public void setGoogleResourceId(String googleResourceId) { this.googleResourceId = googleResourceId; }

    public String getSyncDirection() { return syncDirection; }
    public void setSyncDirection(String syncDirection) { this.syncDirection = syncDirection; }

    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(LocalDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }

    public String getSyncError() { return syncError; }
    public void setSyncError(String syncError) { this.syncError = syncError; }
}
