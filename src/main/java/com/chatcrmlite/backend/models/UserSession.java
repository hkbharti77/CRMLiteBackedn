package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_sessions", indexes = {
    @Index(name = "idx_user_session_token", columnList = "tokenId")
})
public class UserSession implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String tokenId; 

    private String deviceName;
    private String ipAddress;
    
    private String status = "ACTIVE";

    private LocalDateTime lastActiveAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    public UserSession() {}

    public UserSession(UUID id, User user, String tokenId, String deviceName, String ipAddress, String status, LocalDateTime lastActiveAt, LocalDateTime expiresAt, LocalDateTime createdAt) {
        this.id = id;
        this.user = user;
        this.tokenId = tokenId;
        this.deviceName = deviceName;
        this.ipAddress = ipAddress;
        this.status = status != null ? status : "ACTIVE";
        this.lastActiveAt = lastActiveAt;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        lastActiveAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getTokenId() { return tokenId; }
    public void setTokenId(String tokenId) { this.tokenId = tokenId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(LocalDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static UserSessionBuilder builder() {
        return new UserSessionBuilder();
    }

    public static class UserSessionBuilder {
        private UUID id;
        private User user;
        private String tokenId;
        private String deviceName;
        private String ipAddress;
        private String status = "ACTIVE";
        private LocalDateTime lastActiveAt;
        private LocalDateTime expiresAt;
        private LocalDateTime createdAt;

        public UserSessionBuilder id(UUID id) { this.id = id; return this; }
        public UserSessionBuilder user(User user) { this.user = user; return this; }
        public UserSessionBuilder tokenId(String tokenId) { this.tokenId = tokenId; return this; }
        public UserSessionBuilder deviceName(String deviceName) { this.deviceName = deviceName; return this; }
        public UserSessionBuilder ipAddress(String ipAddress) { this.ipAddress = ipAddress; return this; }
        public UserSessionBuilder status(String status) { this.status = status; return this; }
        public UserSessionBuilder lastActiveAt(LocalDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; return this; }
        public UserSessionBuilder expiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; return this; }
        public UserSessionBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public UserSession build() {
            return new UserSession(id, user, tokenId, deviceName, ipAddress, status, lastActiveAt, expiresAt, createdAt);
        }
    }
}
