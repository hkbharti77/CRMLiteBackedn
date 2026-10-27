package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.HallucinationAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface HallucinationAuditLogRepository extends JpaRepository<HallucinationAuditLog, UUID> {
    Page<HallucinationAuditLog> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
}
