package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.PermissionAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PermissionAuditLogRepository extends JpaRepository<PermissionAuditLog, UUID> {
    List<PermissionAuditLog> findByTenantIdAndAgentIdOrderByCreatedAtDesc(UUID tenantId, UUID agentId);
}
