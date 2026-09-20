package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.CommerceCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommerceCatalogRepository extends JpaRepository<CommerceCatalog, UUID> {
    Optional<CommerceCatalog> findByTenantIdAndMetaCatalogId(UUID tenantId, String metaCatalogId);

    @org.springframework.data.jpa.repository.Query("SELECT c FROM CommerceCatalog c WHERE c.id = :id AND c.tenant.id = :tenantId")
    Optional<CommerceCatalog> findByIdAndTenantId(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);

    List<CommerceCatalog> findAllByTenantId(UUID tenantId);
    List<CommerceCatalog> findAllByTenantIdAndStatus(UUID tenantId, String status);
}
