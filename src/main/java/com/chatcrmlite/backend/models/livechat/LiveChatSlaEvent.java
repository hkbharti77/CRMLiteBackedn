package com.chatcrmlite.backend.models.livechat;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "live_chat_sla_events", uniqueConstraints = {
    @UniqueConstraint(name = "uk_sla_queue_escalation", columnNames = {"queue_id", "escalation_type"})
})
public class LiveChatSlaEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "queue_id", nullable = false)
    private UUID queueId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "escalation_type", nullable = false, length = 50)
    private String escalationType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public LiveChatSlaEvent() {}

    public LiveChatSlaEvent(UUID queueId, UUID tenantId, String escalationType) {
        this.queueId = queueId;
        this.tenantId = tenantId;
        this.escalationType = escalationType;
        this.createdAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getQueueId() { return queueId; }
    public UUID getTenantId() { return tenantId; }
    public String getEscalationType() { return escalationType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
