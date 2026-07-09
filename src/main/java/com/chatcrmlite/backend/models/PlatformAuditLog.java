package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable audit log for every platform owner action.
 *
 * Rules:
 * - NO UPDATE or DELETE on this table — append-only.
 * - Every HTTP request to /api/v1/platform/** that mutates state gets an entry.
 * - Read actions (VIEWED_TENANT, SEARCHED) are also logged for investigation purposes.
 * - requestId correlates with the HTTP request trace (MDC/TraceId).
 * - outcome is always SUCCESS or FAILED — never silent.
 */
@Entity
@Table(name = "platform_audit_logs",
       indexes = {
           @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
           @Index(name = "idx_audit_action", columnList = "action"),
           @Index(name = "idx_audit_target", columnList = "target_type,target_id")
       })
public class PlatformAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Correlation ID — same as X-Request-ID / MDC traceId for the HTTP request. */
    @Column(name = "request_id", length = 64)
    private String requestId;

    /**
     * Action performed. Known values:
     * LOGIN, LOGOUT, VIEWED_TENANT, VIEWED_USER, VIEWED_AUDIT_LOGS,
     * SUSPENDED_TENANT, ACTIVATED_TENANT, LOCKED_TENANT, ARCHIVED_TENANT,
     * DELETED_TENANT, CHANGED_QUOTA, DISABLED_USER, ENABLED_USER, SEARCHED,
     * CHANGED_PASSWORD, VIEWED_HEALTH, VIEWED_ANALYTICS
     */
    @Column(nullable = false, length = 50)
    private String action;

    /** SUCCESS or FAILED */
    @Column(nullable = false, length = 10)
    private String outcome;

    /** "Tenant" | "User" | "Subscription" | "System" */
    @Column(name = "target_type", length = 30)
    private String targetType;

    /** UUID or identifier of the affected entity. */
    @Column(name = "target_id", length = 100)
    private String targetId;

    /**
     * JSON snapshot of relevant context.
     * Example: {"reason":"Abuse reported","previousStatus":"ACTIVE"}
     */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** Client IP address (respects X-Forwarded-For for proxied requests). */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    /** Browser + OS string from User-Agent header. Truncated to 300 chars. */
    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();

    public PlatformAuditLog() {}

    // ── Factory method ─────────────────────────────────────────────────────────

    public static PlatformAuditLog of(String requestId, String action, String outcome,
                                       String targetType, String targetId,
                                       String detail, String ipAddress, String userAgent) {
        PlatformAuditLog log = new PlatformAuditLog();
        log.requestId = requestId;
        log.action = action;
        log.outcome = outcome;
        log.targetType = targetType;
        log.targetId = targetId;
        log.detail = detail;
        log.ipAddress = ipAddress;
        log.userAgent = userAgent != null
            ? userAgent.substring(0, Math.min(userAgent.length(), 300))
            : null;
        return log;
    }

    // ── Getters (no setters — immutable after creation) ────────────────────────

    public UUID getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getAction() { return action; }
    public String getOutcome() { return outcome; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getDetail() { return detail; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
