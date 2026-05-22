package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "staff_invites", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"invite_code"})
})
public class StaffInvite implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private User.Role role;

    @Column(name = "invite_code", nullable = false, unique = true)
    private String inviteCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InviteStatus status = InviteStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public StaffInvite() {}

    public StaffInvite(UUID id, Tenant tenant, String email, User.Role role, String inviteCode, InviteStatus status, LocalDateTime expiresAt, LocalDateTime createdAt) {
        this.id = id;
        this.tenant = tenant;
        this.email = email;
        this.role = role;
        this.inviteCode = inviteCode;
        this.status = status != null ? status : InviteStatus.PENDING;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public User.Role getRole() { return role; }
    public void setRole(User.Role role) { this.role = role; }

    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }

    public InviteStatus getStatus() { return status; }
    public void setStatus(InviteStatus status) { this.status = status; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = InviteStatus.PENDING;
        }
    }

    public enum InviteStatus {
        PENDING,
        ACCEPTED,
        EXPIRED
    }

    public static StaffInviteBuilder builder() { return new StaffInviteBuilder(); }

    public static class StaffInviteBuilder {
        private UUID id;
        private Tenant tenant;
        private String email;
        private User.Role role;
        private String inviteCode;
        private InviteStatus status = InviteStatus.PENDING;
        private LocalDateTime expiresAt;
        private LocalDateTime createdAt;

        public StaffInviteBuilder id(UUID id) { this.id = id; return this; }
        public StaffInviteBuilder tenant(Tenant tenant) { this.tenant = tenant; return this; }
        public StaffInviteBuilder email(String email) { this.email = email; return this; }
        public StaffInviteBuilder role(User.Role role) { this.role = role; return this; }
        public StaffInviteBuilder inviteCode(String inviteCode) { this.inviteCode = inviteCode; return this; }
        public StaffInviteBuilder status(InviteStatus status) { this.status = status; return this; }
        public StaffInviteBuilder expiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; return this; }
        public StaffInviteBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public StaffInvite build() {
            return new StaffInvite(id, tenant, email, role, inviteCode, status, expiresAt, createdAt);
        }
    }
}
