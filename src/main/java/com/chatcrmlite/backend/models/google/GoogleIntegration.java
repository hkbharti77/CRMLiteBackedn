package com.chatcrmlite.backend.models.google;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks which CRM features (Calendar, Gmail, Drive, etc.) are enabled
 * against a GoogleConnection. One connection can cover multiple features
 * if the user granted multiple scopes in the same OAuth flow.
 *
 * Design decisions:
 * - Separate from GoogleConnection so status can be tracked per-feature independently.
 * - status uses GoogleIntegrationStatus for meaningful frontend display (PARTIAL, REAUTH_REQUIRED, etc.)
 * - disconnectedAt records when the user explicitly disconnected a specific feature.
 */
@Entity
@Table(name = "google_integrations", indexes = {
    @Index(name = "idx_gint_connection_id", columnList = "connection_id"),
    @Index(name = "idx_gint_feature", columnList = "feature")
})
public class GoogleIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** FK → GoogleConnection (the credential set that authorized this feature) */
    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    /** Which CRM integration feature this row represents */
    @Enumerated(EnumType.STRING)
    @Column(name = "feature", nullable = false, length = 30)
    private GoogleIntegrationType feature;

    /** Current status of this integration */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private GoogleIntegrationStatus status;

    /** When this feature was successfully connected */
    @Column(name = "connected_at")
    private LocalDateTime connectedAt;

    /** When this feature was explicitly disconnected (null if still active) */
    @Column(name = "disconnected_at")
    private LocalDateTime disconnectedAt;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }

    public GoogleIntegrationType getFeature() { return feature; }
    public void setFeature(GoogleIntegrationType feature) { this.feature = feature; }

    public GoogleIntegrationStatus getStatus() { return status; }
    public void setStatus(GoogleIntegrationStatus status) { this.status = status; }

    public LocalDateTime getConnectedAt() { return connectedAt; }
    public void setConnectedAt(LocalDateTime connectedAt) { this.connectedAt = connectedAt; }

    public LocalDateTime getDisconnectedAt() { return disconnectedAt; }
    public void setDisconnectedAt(LocalDateTime disconnectedAt) { this.disconnectedAt = disconnectedAt; }
}
