package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "user_permission_audit_logs", indexes = {
    @Index(name = "idx_perm_audit_tenant_agent", columnList = "tenant_id, agent_id"),
    @Index(name = "idx_perm_audit_created_at", columnList = "created_at DESC")
})
public class PermissionAuditLog implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "changed_by_id", nullable = false)
    private UUID changedById;

    @Column(name = "action", nullable = false, length = 50)
    private String action = "UPDATE_PERMISSIONS";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_permissions", columnDefinition = "jsonb")
    private List<String> oldPermissions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_permissions", columnDefinition = "jsonb")
    private List<String> newPermissions;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "permission_version", nullable = false)
    private Integer permissionVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public PermissionAuditLog() {}

    public PermissionAuditLog(UUID tenantId, UUID agentId, UUID changedById, String action, List<String> oldPermissions, List<String> newPermissions, String reason, String requestId, String ipAddress, String userAgent, Integer permissionVersion) {
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.changedById = changedById;
        this.action = action != null ? action : "UPDATE_PERMISSIONS";
        this.oldPermissions = oldPermissions;
        this.newPermissions = newPermissions;
        this.reason = reason;
        this.requestId = requestId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.permissionVersion = permissionVersion;
        this.createdAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getAgentId() { return agentId; }
    public UUID getChangedById() { return changedById; }
    public String getAction() { return action; }
    public List<String> getOldPermissions() { return oldPermissions; }
    public List<String> getNewPermissions() { return newPermissions; }
    public String getReason() { return reason; }
    public String getRequestId() { return requestId; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public Integer getPermissionVersion() { return permissionVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
