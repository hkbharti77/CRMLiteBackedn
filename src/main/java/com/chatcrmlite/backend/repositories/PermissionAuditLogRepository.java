package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.PermissionAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PermissionAuditLogRepository extends JpaRepository<PermissionAuditLog, UUID> {
    @org.springframework.data.jpa.repository.Query("SELECT p FROM PermissionAuditLog p WHERE p.tenantId = :tenantId AND p.agentId = :agentId ORDER BY p.createdAt DESC")
    List<PermissionAuditLog> findByTenantIdAndAgentIdOrderByCreatedAtDesc(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId, @org.springframework.data.repository.query.Param("agentId") UUID agentId);
}
