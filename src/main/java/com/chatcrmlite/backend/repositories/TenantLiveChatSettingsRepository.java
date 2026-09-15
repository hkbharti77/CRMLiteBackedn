package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.livechat.TenantLiveChatSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantLiveChatSettingsRepository extends JpaRepository<TenantLiveChatSettings, UUID> {
    Optional<TenantLiveChatSettings> findByTenant(Tenant tenant);
    @org.springframework.data.jpa.repository.Query("SELECT s FROM TenantLiveChatSettings s WHERE s.tenant.id = :tenantId")
    Optional<TenantLiveChatSettings> findByTenantId(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
}
