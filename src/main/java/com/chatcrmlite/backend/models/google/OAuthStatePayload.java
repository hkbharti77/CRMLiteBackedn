package com.chatcrmlite.backend.models.google;

import java.time.Instant;
import java.util.UUID;

/**
 * Value object stored in Redis during an OAuth authorization flow.
 * Binds a random state string to the user + integration type to prevent CSRF.
 *
 * TTL: 10 minutes (enforced by Redis key expiry)
 * Usage: single-use — fetched and deleted atomically on callback (no replay possible)
 */
public class OAuthStatePayload implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private UUID userId;
    private GoogleIntegrationType integration;
    private Instant createdAt;
    private Instant expiresAt;

    public OAuthStatePayload() {}

    public OAuthStatePayload(UUID userId, GoogleIntegrationType integration) {
        this.userId = userId;
        this.integration = integration;
        this.createdAt = Instant.now();
        this.expiresAt = Instant.now().plusSeconds(600); // 10 minutes
    }

    /** Returns true if this state has passed its expiry time. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public GoogleIntegrationType getIntegration() { return integration; }
    public void setIntegration(GoogleIntegrationType integration) { this.integration = integration; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
