package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.livechat.LiveChatAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LiveChatAuditLogRepository extends JpaRepository<LiveChatAuditLog, UUID> {
    @org.springframework.data.jpa.repository.Query("SELECT l FROM LiveChatAuditLog l WHERE l.tenantId = :tenantId AND l.contactId = :contactId ORDER BY l.timestamp DESC")
    List<LiveChatAuditLog> findAllByTenantIdAndContactIdOrderByTimestampDesc(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId, @org.springframework.data.repository.query.Param("contactId") UUID contactId);

    @org.springframework.data.jpa.repository.Query("SELECT l FROM LiveChatAuditLog l WHERE l.tenantId = :tenantId ORDER BY l.timestamp DESC")
    List<LiveChatAuditLog> findAllByTenantIdOrderByTimestampDesc(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
}
