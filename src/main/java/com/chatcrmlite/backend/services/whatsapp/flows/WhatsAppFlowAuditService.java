package com.chatcrmlite.backend.services.whatsapp.flows;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.flows.FlowAuditAction;
import com.chatcrmlite.backend.models.flows.WhatsAppFlowAuditLog;
import com.chatcrmlite.backend.repositories.flows.WhatsAppFlowAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
public class WhatsAppFlowAuditService {

    private final WhatsAppFlowAuditLogRepository auditLogRepository;

    public WhatsAppFlowAuditService(WhatsAppFlowAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(UUID flowId, UUID revisionId, User actor, FlowAuditAction action, String oldStatus, String newStatus, String metadataJson) {
        try {
            WhatsAppFlowAuditLog logEntry = WhatsAppFlowAuditLog.builder()
                    .flowId(flowId)
                    .revisionId(revisionId)
                    .actorId(actor != null ? actor.getId() : null)
                    .action(action)
                    .oldStatus(oldStatus)
                    .newStatus(newStatus)
                    .metadataJson(metadataJson)
                    .build();
            if (actor != null && actor.getTenant() != null) {
                logEntry.setTenant(actor.getTenant());
            }
            auditLogRepository.save(logEntry);
            log.info("📋 [FlowAudit] Action={} for FlowId={} RevisionId={} Actor={}", action, flowId, revisionId, (actor != null ? actor.getEmail() : "SYSTEM"));
        } catch (Exception e) {
            log.warn("⚠️ [FlowAudit] Failed to persist audit log: {}", e.getMessage());
        }
    }
}
