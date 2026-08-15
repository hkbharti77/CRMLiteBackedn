package com.chatcrmlite.backend.repositories.flows;

import com.chatcrmlite.backend.models.flows.WhatsAppFlowAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WhatsAppFlowAuditLogRepository extends JpaRepository<WhatsAppFlowAuditLog, UUID> {

    @Query("SELECT a FROM WhatsAppFlowAuditLog a WHERE a.flowId = :flowId ORDER BY a.createdAt DESC")
    List<WhatsAppFlowAuditLog> findAllByFlowIdOrderByCreatedAtDesc(@Param("flowId") UUID flowId);

    @Query("SELECT a FROM WhatsAppFlowAuditLog a WHERE a.tenant.id = :tenantId ORDER BY a.createdAt DESC")
    List<WhatsAppFlowAuditLog> findAllByTenantIdOrderByCreatedAtDesc(@Param("tenantId") UUID tenantId);
}
