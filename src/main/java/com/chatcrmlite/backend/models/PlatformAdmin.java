package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Single-row entity representing the platform owner (Super Admin).
 *
 * Design decisions:
 * - Deliberately NOT part of the tenant user system (app_users).
 *   There is no tenant FK — this is a platform-level identity.
 * - failedLoginCount + lockedUntil enable brute-force lockout:
 *   5 consecutive failures → 15-minute lock.
 * - Password is bcrypt-hashed (BCryptPasswordEncoder cost 12).
 * - Phase 2: totpSecret + backup codes will be added here.
 */
@Entity
@Table(name = "platform_admin")
public class PlatformAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    private String displayName;

    /** Increments on each failed login. Resets to 0 on success. */
    @Column(nullable = false)
    private int failedLoginCount = 0;

    /** NULL = not locked. Non-null = locked until this timestamp. */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Phase 2: String totpSecret; (hashed TOTP for 2FA)

    public PlatformAdmin() {}

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public int getFailedLoginCount() { return failedLoginCount; }
    public void setFailedLoginCount(int failedLoginCount) { this.failedLoginCount = failedLoginCount; }

    public LocalDateTime getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(LocalDateTime lockedUntil) { this.lockedUntil = lockedUntil; }

    public LocalDateTime getLastLogin() { return lastLogin; }
    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    /** Returns true if the account is currently locked (lockout period has not expired). */
    public boolean isCurrentlyLocked() {
        return lockedUntil != null && LocalDateTime.now().isBefore(lockedUntil);
    }

    /** Returns remaining lockout seconds, or 0 if not locked. */
    public long remainingLockSeconds() {
        if (!isCurrentlyLocked()) return 0;
        return java.time.Duration.between(LocalDateTime.now(), lockedUntil).getSeconds();
    }
}
