package com.chatcrmlite.backend.models.livechat;

import com.chatcrmlite.backend.models.Tenant;
import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenant_live_chat_settings", indexes = {
    @Index(name = "idx_tenant_lc_settings", columnList = "tenant_id")
})
public class TenantLiveChatSettings implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum RoutingStrategy {
        AGENT_ONLY_THEN_QUEUE,
        AGENT_ADMIN_OWNER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, unique = true)
    private Tenant tenant;

    @Column(name = "max_concurrent_chats", nullable = false)
    private Integer maxConcurrentChats = 2;

    @Column(name = "sla_minutes", nullable = false)
    private Integer slaMinutes = 30;

    @Column(name = "heartbeat_timeout_seconds", nullable = false)
    private Integer heartbeatTimeoutSeconds = 120;

    @Enumerated(EnumType.STRING)
    @Column(name = "routing_strategy", nullable = false)
    private RoutingStrategy routingStrategy = RoutingStrategy.AGENT_ONLY_THEN_QUEUE;

    @Column(name = "allow_forced_takeover", nullable = false)
    private Boolean allowForcedTakeover = true;

    @Column(name = "allow_agent_transfer", nullable = false)
    private Boolean allowAgentTransfer = true;

    @Column(name = "auto_resume_bot_on_resolve", nullable = false)
    private Boolean autoResumeBotOnResolve = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public TenantLiveChatSettings() {}

    public TenantLiveChatSettings(Tenant tenant) {
        this.tenant = tenant;
        this.maxConcurrentChats = 2;
        this.slaMinutes = 30;
        this.heartbeatTimeoutSeconds = 120;
        this.routingStrategy = RoutingStrategy.AGENT_ONLY_THEN_QUEUE;
        this.allowForcedTakeover = true;
        this.allowAgentTransfer = true;
        this.autoResumeBotOnResolve = true;
        this.createdAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    public Integer getMaxConcurrentChats() { return maxConcurrentChats != null ? maxConcurrentChats : 2; }
    public void setMaxConcurrentChats(Integer maxConcurrentChats) { this.maxConcurrentChats = maxConcurrentChats; }

    public Integer getSlaMinutes() { return slaMinutes != null ? slaMinutes : 30; }
    public void setSlaMinutes(Integer slaMinutes) { this.slaMinutes = slaMinutes; }

    public Integer getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds != null ? heartbeatTimeoutSeconds : 120; }
    public void setHeartbeatTimeoutSeconds(Integer heartbeatTimeoutSeconds) { this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds; }

    public RoutingStrategy getRoutingStrategy() { return routingStrategy != null ? routingStrategy : RoutingStrategy.AGENT_ONLY_THEN_QUEUE; }
    public void setRoutingStrategy(RoutingStrategy routingStrategy) { this.routingStrategy = routingStrategy; }

    public Boolean getAllowForcedTakeover() { return allowForcedTakeover != null ? allowForcedTakeover : true; }
    public void setAllowForcedTakeover(Boolean allowForcedTakeover) { this.allowForcedTakeover = allowForcedTakeover; }

    public Boolean getAllowAgentTransfer() { return allowAgentTransfer != null ? allowAgentTransfer : true; }
    public void setAllowAgentTransfer(Boolean allowAgentTransfer) { this.allowAgentTransfer = allowAgentTransfer; }

    public Boolean getAutoResumeBotOnResolve() { return autoResumeBotOnResolve != null ? autoResumeBotOnResolve : true; }
    public void setAutoResumeBotOnResolve(Boolean autoResumeBotOnResolve) { this.autoResumeBotOnResolve = autoResumeBotOnResolve; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
