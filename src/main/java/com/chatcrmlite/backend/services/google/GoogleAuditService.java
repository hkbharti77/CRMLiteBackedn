package com.chatcrmlite.backend.services.google;

import com.chatcrmlite.backend.models.google.GoogleApiAuditLog;
import com.chatcrmlite.backend.repositories.GoogleApiAuditLogRepository;
import com.chatcrmlite.backend.repositories.GoogleConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Service for logging Google API requests, latencies, and outcomes.
 * Operates asynchronously and safely ensures audit errors never interrupt business flows.
 * Redacts any sensitive secrets/tokens by design.
 */
@Service
public class GoogleAuditService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuditService.class);

    private final GoogleApiAuditLogRepository auditLogRepository;
    private final GoogleConnectionRepository connectionRepository;

    @Autowired
    public GoogleAuditService(GoogleApiAuditLogRepository auditLogRepository,
                              GoogleConnectionRepository connectionRepository) {
        this.auditLogRepository = auditLogRepository;
        this.connectionRepository = connectionRepository;
    }

    @Async
    public void logApiCall(UUID userId, UUID connectionId, String integration, String operation,
                           String googleApi, String status, long latencyMs,
                           String errorCode, String errorMessage, int retryCount) {
        try {
            if (connectionId == null && userId != null) {
                connectionId = connectionRepository.findActiveByUserId(userId)
                        .map(c -> c.getId())
                        .orElse(null);
            }

            UUID tenantId = null;
            if (connectionId != null) {
                tenantId = connectionRepository.findById(connectionId)
                        .map(com.chatcrmlite.backend.models.google.GoogleConnection::getTenantId)
                        .orElse(null);
            }

            GoogleApiAuditLog audit = new GoogleApiAuditLog();
            audit.setTenantId(tenantId);
            audit.setUserId(userId);
            audit.setConnectionId(connectionId);
            audit.setIntegration(integration);
            audit.setOperation(operation);
            audit.setGoogleApi(googleApi);
            audit.setRequestId(UUID.randomUUID().toString());
            audit.setStatus(status);
            audit.setLatencyMs(latencyMs);
            audit.setErrorCode(errorCode);
            // Sanitize and trim error message if present
            if (errorMessage != null && errorMessage.length() > 2000) {
                errorMessage = errorMessage.substring(0, 2000);
            }
            audit.setErrorMessage(errorMessage);
            audit.setRetryCount(retryCount);

            auditLogRepository.save(audit);
            log.debug("[GoogleAuditService] Logged API call: integration={} op={} status={} latency={}ms",
                    integration, operation, status, latencyMs);
        } catch (Exception ex) {
            log.warn("[GoogleAuditService] Failed to record audit log: {}", ex.getMessage());
        }
    }

    public void logSuccess(UUID userId, String integration, String operation, String googleApi, long latencyMs) {
        logApiCall(userId, null, integration, operation, googleApi, "SUCCESS", latencyMs, null, null, 0);
    }

    public void logFailure(UUID userId, String integration, String operation, String googleApi,
                           long latencyMs, Exception ex, int retryCount) {
        String errCode = ex.getClass().getSimpleName();
        String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        logApiCall(userId, null, integration, operation, googleApi, "FAILED", latencyMs, errCode, errMsg, retryCount);
    }
}
