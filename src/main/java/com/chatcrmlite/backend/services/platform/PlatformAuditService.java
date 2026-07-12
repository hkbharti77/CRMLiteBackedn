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

    private String resolveIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
