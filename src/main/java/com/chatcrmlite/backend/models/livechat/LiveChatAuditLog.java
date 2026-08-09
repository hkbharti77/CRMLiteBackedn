package com.chatcrmlite.backend.models.livechat;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "live_chat_audit_logs", indexes = {
    @Index(name = "idx_audit_tenant_contact", columnList = "tenant_id, contact_id"),
    @Index(name = "idx_audit_action", columnList = "action, timestamp")
})
public class LiveChatAuditLog implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum AuditAction {
        CHAT_REQUESTED,
        CHAT_QUEUED,
        CHAT_ASSIGNED,
        CHAT_TRANSFERRED,
        CHAT_TAKEN_OVER,
        CHAT_RESOLVED,
        CHAT_REOPENED,
        SLA_BREACHED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private AuditAction action;

    @Column(name = "from_user_id")
    private UUID fromUserId;

    @Column(name = "to_user_id")
    private UUID toUserId;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime timestamp = LocalDateTime.now();

    public LiveChatAuditLog() {}

    public LiveChatAuditLog(UUID tenantId, UUID contactId, UUID actorUserId, AuditAction action, UUID fromUserId, UUID toUserId, String requestId, String metadata) {
        this.tenantId = tenantId;
        this.contactId = contactId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.requestId = requestId;
        this.metadata = metadata;
        this.timestamp = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getContactId() { return contactId; }
    public UUID getActorUserId() { return actorUserId; }
    public AuditAction getAction() { return action; }
    public UUID getFromUserId() { return fromUserId; }
    public UUID getToUserId() { return toUserId; }
    public String getRequestId() { return requestId; }
    public String getMetadata() { return metadata; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
