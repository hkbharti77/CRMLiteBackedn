package com.chatcrmlite.backend.services.livechat;

import com.chatcrmlite.backend.models.livechat.LiveChatAuditLog;
import com.chatcrmlite.backend.repositories.LiveChatAuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class LiveChatAuditService {

    @Autowired
    private LiveChatAuditLogRepository auditLogRepository;

    public LiveChatAuditLog recordAudit(UUID tenantId, UUID contactId, UUID actorUserId, LiveChatAuditLog.AuditAction action, UUID fromUserId, UUID toUserId, String requestId, String metadata) {
        LiveChatAuditLog audit = new LiveChatAuditLog(tenantId, contactId, actorUserId, action, fromUserId, toUserId, requestId, metadata);
        return auditLogRepository.save(audit);
    }
}
