package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.livechat.LiveChatAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LiveChatAuditLogRepository extends JpaRepository<LiveChatAuditLog, UUID> {
    List<LiveChatAuditLog> findAllByTenantIdAndContactIdOrderByTimestampDesc(UUID tenantId, UUID contactId);
    List<LiveChatAuditLog> findAllByTenantIdOrderByTimestampDesc(UUID tenantId);
}
