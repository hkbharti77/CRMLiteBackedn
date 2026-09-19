package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.CatalogStatus;
import com.chatcrmlite.backend.models.TenantAiCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantAiCatalogRepository extends JpaRepository<TenantAiCatalog, UUID> {

    @Query("SELECT c FROM TenantAiCatalog c WHERE c.id = :id AND c.tenant.id = :tenantId")
    Optional<TenantAiCatalog> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT c FROM TenantAiCatalog c WHERE c.tenant.id = :tenantId AND c.status = :status ORDER BY c.createdAt DESC")
    List<TenantAiCatalog> findByTenantIdAndStatus(@Param("tenantId") UUID tenantId, @Param("status") CatalogStatus status);

    @Query("SELECT c FROM TenantAiCatalog c WHERE c.tenant.id = :tenantId ORDER BY c.createdAt DESC")
    List<TenantAiCatalog> findByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT c FROM TenantAiCatalog c WHERE c.tenant.id = :tenantId AND c.status = 'ACTIVE' ORDER BY c.createdAt DESC")
    List<TenantAiCatalog> findActiveByTenantId(@Param("tenantId") UUID tenantId);
}
