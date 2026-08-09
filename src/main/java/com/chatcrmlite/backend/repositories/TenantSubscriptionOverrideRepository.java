package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.TenantSubscriptionOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface TenantSubscriptionOverrideRepository extends JpaRepository<TenantSubscriptionOverride, UUID> {
    Optional<TenantSubscriptionOverride> findByTenantId(UUID tenantId);
    void deleteByTenantId(UUID tenantId);
}
