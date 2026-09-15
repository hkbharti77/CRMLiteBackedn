package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.TenantSubscriptionOverrideAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface TenantSubscriptionOverrideAuditRepository extends JpaRepository<TenantSubscriptionOverrideAudit, UUID> {
    @Query("SELECT a FROM TenantSubscriptionOverrideAudit a WHERE a.tenant.id = :tenantId ORDER BY a.createdAt DESC")
    List<TenantSubscriptionOverrideAudit> findByTenantIdOrderByCreatedAtDesc(@Param("tenantId") UUID tenantId);
}
