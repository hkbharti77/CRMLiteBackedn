package com.chatcrmlite.backend.services.platform;

import com.chatcrmlite.backend.models.PlatformAuditLog;
import com.chatcrmlite.backend.repositories.PlatformAuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Convenience service for recording platform audit events. */
@Service
public class PlatformAuditService {

    private final PlatformAuditLogRepository repository;

    public PlatformAuditService(PlatformAuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(String action, String outcome,
                       String targetType, String targetId, String detail,
                       HttpServletRequest request) {
        String requestId = java.util.UUID.randomUUID().toString();
        String ip = resolveIp(request);
        String ua = request.getHeader("User-Agent");
        repository.save(PlatformAuditLog.of(requestId, action, outcome, targetType, targetId, detail, ip, ua));
    }

    public Page<PlatformAuditLog> findFiltered(String action, String targetType,
                                                LocalDateTime from, LocalDateTime to,
                                                Pageable pageable) {
        return repository.findFiltered(action, targetType, from, to, pageable);
    }

    public Page<PlatformAuditLog> findRecent(Pageable pageable) {
        return repository.findRecent(pageable);
    }

    public void recordTenantLogin(String email, String tenantId, String outcome, HttpServletRequest request) {
        String detail = String.format("{\"userEmail\":\"%s\",\"tenantId\":\"%s\",\"event\":\"TENANT_LOGIN\"}", email, tenantId != null ? tenantId : "N/A");
        record("TENANT_LOGIN", outcome, "Tenant", tenantId != null ? tenantId : email, detail, request);
    }

    public void recordMetaConnection(String tenantId, String action, String wabaId, String phoneId, String status, HttpServletRequest request) {
        String detail = String.format("{\"wabaId\":\"%s\",\"phoneNumberId\":\"%s\",\"status\":\"%s\"}", 
            wabaId != null ? wabaId : "", phoneId != null ? phoneId : "", status != null ? status : "");
        record(action, "SUCCESS", "MetaWhatsApp", tenantId != null ? tenantId : "N/A", detail, request);
    }

    public void recordFeatureToggle(String tenantId, String featureName, boolean enabled, HttpServletRequest request) {
        String detail = String.format("{\"feature\":\"%s\",\"enabled\":%b}", featureName, enabled);
        String actionName = "TOGGLE_" + featureName.toUpperCase();
        record(actionName, "SUCCESS", "FeatureToggle", tenantId != null ? tenantId : "N/A", detail, request);
    }

    public void recordSubscriptionEvent(String tenantId, String action, String planId, String detailText, HttpServletRequest request) {
        String detail = String.format("{\"planId\":\"%s\",\"info\":\"%s\"}", planId != null ? planId : "", detailText != null ? detailText : "");
        record(action, "SUCCESS", "Subscription", tenantId != null ? tenantId : "N/A", detail, request);
    }

    private String resolveIp(HttpServletRequest request) {
        if (request == null) return "0.0.0.0";
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
