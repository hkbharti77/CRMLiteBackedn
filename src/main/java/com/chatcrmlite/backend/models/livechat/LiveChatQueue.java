package com.chatcrmlite.backend.models.livechat;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "live_chat_queues", indexes = {
    @Index(name = "idx_queue_tenant_status_queued", columnList = "tenant_id, status, queued_at"),
    @Index(name = "idx_queue_sla", columnList = "sla_breached, sla_expires_at")
})
public class LiveChatQueue implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum QueueStatus {
        QUEUED,
        ASSIGNED,
        EXPIRED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false)
    private Contact contact;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QueueStatus status = QueueStatus.QUEUED;

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt = LocalDateTime.now();

    @Column(name = "sla_expires_at", nullable = false)
    private LocalDateTime slaExpiresAt;

    @Column(name = "sla_breached", nullable = false)
    private boolean slaBreached = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public LiveChatQueue() {}

    public LiveChatQueue(Tenant tenant, Contact contact, LocalDateTime slaExpiresAt) {
        this.tenant = tenant;
        this.contact = contact;
        this.status = QueueStatus.QUEUED;
        this.queuedAt = LocalDateTime.now();
        this.slaExpiresAt = slaExpiresAt;
        this.createdAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    public Contact getContact() { return contact; }
    public void setContact(Contact contact) { this.contact = contact; }

    public QueueStatus getStatus() { return status; }
    public void setStatus(QueueStatus status) { this.status = status; }

    public LocalDateTime getQueuedAt() { return queuedAt; }
    public void setQueuedAt(LocalDateTime queuedAt) { this.queuedAt = queuedAt; }

    public LocalDateTime getSlaExpiresAt() { return slaExpiresAt; }
    public void setSlaExpiresAt(LocalDateTime slaExpiresAt) { this.slaExpiresAt = slaExpiresAt; }

    public boolean isSlaBreached() { return slaBreached; }
    public void setSlaBreached(boolean slaBreached) { this.slaBreached = slaBreached; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
