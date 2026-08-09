package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.TenantSubscriptionOverrideAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface TenantSubscriptionOverrideAuditRepository extends JpaRepository<TenantSubscriptionOverrideAudit, UUID> {
    List<TenantSubscriptionOverrideAudit> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
