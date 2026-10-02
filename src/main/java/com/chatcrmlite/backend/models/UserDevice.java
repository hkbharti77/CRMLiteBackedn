package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks per-device FCM push notification tokens for a CRMLite user.
 * One user can have multiple devices (Chrome on laptop, Android app, etc.)
 *
 * Design decisions:
 * - installationId is the Firebase Installation ID (FID) — stable per browser/app install.
 * - revokedAt == null means the device is active.
 * - Stale devices (UNREGISTERED FCM response) are soft-deleted by setting revokedAt.
 * - Frontend must send both installationId AND fcmToken on every registration.
 *   Upsert by installationId — do not create duplicates per device.
 */
@Entity
@Table(name = "user_devices", indexes = {
    @Index(name = "idx_udev_user_id", columnList = "user_id"),
    @Index(name = "idx_udev_installation_id", columnList = "installation_id")
})
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    /** Firebase Installation ID — stable per browser/app install. Used as upsert key. */
    @Column(name = "installation_id", nullable = false, length = 200)
    private String installationId;

    /** Firebase Cloud Messaging registration token — used to send push notifications */
    @Column(name = "fcm_token", nullable = false, columnDefinition = "TEXT")
    private String fcmToken;

    /** Platform: WEB | ANDROID | IOS */
    @Column(name = "platform", length = 20)
    private String platform;

    /** Browser user agent string */
    @Column(name = "browser", columnDefinition = "TEXT")
    private String browser;

    /** Human-readable device name (optional, from client) */
    @Column(name = "device_name", length = 200)
    private String deviceName;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** Null = active. Set to now() when FCM returns UNREGISTERED or user logs out. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        lastSeenAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getInstallationId() { return installationId; }
    public void setInstallationId(String installationId) { this.installationId = installationId; }

    public String getFcmToken() { return fcmToken; }
    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getBrowser() { return browser; }
    public void setBrowser(String browser) { this.browser = browser; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
}
