package com.chatcrmlite.backend.models.chat;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "canned_responses", uniqueConstraints = {
    @UniqueConstraint(name = "uk_tenant_shortcut", columnNames = {"tenant_id", "shortcut"})
})
public class CannedResponse extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 50)
    private String shortcut;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(length = 50)
    private String category = "GENERAL";

    @Column(name = "is_shared", nullable = false)
    private boolean isShared = true;

    @Column(name = "usage_count", nullable = false)
    private Long usageCount = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CannedResponse() {}

    public CannedResponse(UUID id, Tenant tenant, String title, String shortcut, String content, String category, boolean isShared, Long usageCount, User createdBy) {
        this.id = id;
        if (tenant != null) {
            setTenant(tenant);
        }
        this.title = title;
        this.shortcut = shortcut;
        this.content = content;
        this.category = category != null ? category : "GENERAL";
        this.isShared = isShared;
        this.usageCount = usageCount != null ? usageCount : 0L;
        this.createdBy = createdBy;
    }

    @PrePersist
    @Override
    protected void populateTenant() {
        super.populateTenant();
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
        if (this.usageCount == null) this.usageCount = 0L;
        if (this.category == null) this.category = "GENERAL";
        if (this.shortcut != null) {
            this.shortcut = this.shortcut.trim().toLowerCase();
            if (this.shortcut.startsWith("/")) {
                this.shortcut = this.shortcut.substring(1);
            }
        }
    }

    @PreUpdate
    protected void onPreUpdate() {
        populateTenant();
        this.updatedAt = LocalDateTime.now();
        if (this.shortcut != null) {
            this.shortcut = this.shortcut.trim().toLowerCase();
            if (this.shortcut.startsWith("/")) {
                this.shortcut = this.shortcut.substring(1);
            }
        }
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getShortcut() { return shortcut; }
    public void setShortcut(String shortcut) {
        if (shortcut != null) {
            String clean = shortcut.trim().toLowerCase();
            this.shortcut = clean.startsWith("/") ? clean.substring(1) : clean;
        } else {
            this.shortcut = null;
        }
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public boolean isShared() { return isShared; }
    public void setShared(boolean shared) { isShared = shared; }

    public Long getUsageCount() { return usageCount; }
    public void setUsageCount(Long usageCount) { this.usageCount = usageCount; }

    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
