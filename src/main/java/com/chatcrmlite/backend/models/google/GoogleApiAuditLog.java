package com.chatcrmlite.backend.models.google;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Audit log recording Google API interactions, latencies, and statuses.
 * NEVER stores raw access tokens, refresh tokens, or user secrets.
 */
@Entity
@Table(name = "google_api_audit_logs", indexes = {
    @Index(name = "idx_gaudit_user_id", columnList = "user_id"),
    @Index(name = "idx_gaudit_integration", columnList = "integration"),
    @Index(name = "idx_gaudit_status", columnList = "status"),
    @Index(name = "idx_gaudit_created_at", columnList = "created_at")
})
public class GoogleApiAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "connection_id")
    private UUID connectionId;

    @Column(name = "integration", length = 50)
    private String integration;

    @Column(name = "operation", length = 100)
    private String operation;

    @Column(name = "google_api", length = 100)
    private String googleApi;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "status", length = 30)
    private String status;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public GoogleApiAuditLog() {}

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }

    public String getIntegration() { return integration; }
    public void setIntegration(String integration) { this.integration = integration; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public String getGoogleApi() { return googleApi; }
    public void setGoogleApi(String googleApi) { this.googleApi = googleApi; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
