package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.TenantSubscriptionOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface TenantSubscriptionOverrideRepository extends JpaRepository<TenantSubscriptionOverride, UUID> {
    @Query("SELECT t FROM TenantSubscriptionOverride t WHERE t.tenant.id = :tenantId")
    Optional<TenantSubscriptionOverride> findByTenantId(@Param("tenantId") UUID tenantId);

    @Modifying
    @Query("DELETE FROM TenantSubscriptionOverride t WHERE t.tenant.id = :tenantId")
    void deleteByTenantId(@Param("tenantId") UUID tenantId);
}
