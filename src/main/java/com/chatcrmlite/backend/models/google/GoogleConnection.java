package com.chatcrmlite.backend.models.google;

import com.chatcrmlite.backend.utils.EncryptionConverter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stores the OAuth2 credential set (access token + refresh token) for a CRMLite user's
 * connected Google account.
 *
 * Design decisions:
 * - One row per OAuth token credential set. One token set can cover multiple scopes.
 * - Both tokens are AES-256 encrypted at rest via EncryptionConverter.
 * - revokedAt == null means the connection is active.
 * - grantedScopes stores exactly what Google returned after user consent — used to detect
 *   PARTIAL grants when a user unchecks a requested scope.
 * - This entity replaces the old googleAccessToken/googleRefreshToken/googleTokenExpiry
 *   fields that were stored directly on User.java.
 */
@Entity
@Table(name = "google_connections", indexes = {
    @Index(name = "idx_gconn_user_id", columnList = "user_id"),
    @Index(name = "idx_gconn_google_subject_id", columnList = "google_subject_id")
})
public class GoogleConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    /** Google "sub" claim — stable, immutable identifier for the Google account */
    @Column(name = "google_subject_id")
    private String googleSubjectId;

    /** AES-256 encrypted OAuth2 access token */
    @Convert(converter = EncryptionConverter.class)
    @Column(name = "access_token_encrypted", columnDefinition = "TEXT")
    private String accessTokenEncrypted;

    /** AES-256 encrypted OAuth2 refresh token — used to get new access tokens */
    @Convert(converter = EncryptionConverter.class)
    @Column(name = "refresh_token_encrypted", columnDefinition = "TEXT")
    private String refreshTokenEncrypted;

    /** When the current access token expires */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * Space-separated scopes actually granted by Google.
     * May differ from requested scopes if the user unchecked some (granular permissions).
     * Example: "https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/gmail.send"
     */
    @Column(name = "granted_scopes", columnDefinition = "TEXT")
    private String grantedScopes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Null = connection is active. Set to now() when disconnected or revoked. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    /** Returns true if this connection is still active (not revoked). */
    public boolean isActive() {
        return revokedAt == null;
    }

    /** Returns true if the access token is expired or will expire within 5 minutes. */
    public boolean isAccessTokenExpired() {
        return expiresAt == null || LocalDateTime.now().isAfter(expiresAt.minusMinutes(5));
    }

    /** Returns true if the granted scopes contain the given scope string. */
    public boolean hasScope(String scope) {
        return grantedScopes != null && grantedScopes.contains(scope);
    }

    /** Alias for hasScope */
    public boolean hasGrantedScope(String scope) {
        return hasScope(scope);
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getGoogleSubjectId() { return googleSubjectId; }
    public void setGoogleSubjectId(String googleSubjectId) { this.googleSubjectId = googleSubjectId; }

    public String getAccessTokenEncrypted() { return accessTokenEncrypted; }
    public void setAccessTokenEncrypted(String accessTokenEncrypted) { this.accessTokenEncrypted = accessTokenEncrypted; }

    public String getRefreshTokenEncrypted() { return refreshTokenEncrypted; }
    public void setRefreshTokenEncrypted(String refreshTokenEncrypted) { this.refreshTokenEncrypted = refreshTokenEncrypted; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public String getGrantedScopes() { return grantedScopes; }
    public void setGrantedScopes(String grantedScopes) { this.grantedScopes = grantedScopes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
}
