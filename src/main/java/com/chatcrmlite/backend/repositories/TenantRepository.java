package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    java.util.List<Tenant> findAllByLifecycleStatus(Tenant.LifecycleStatus status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT t FROM Tenant t WHERE t.id = :id")
    java.util.Optional<Tenant> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}
