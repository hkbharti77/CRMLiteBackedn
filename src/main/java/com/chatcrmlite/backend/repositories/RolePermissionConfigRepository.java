package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.RolePermissionConfig;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RolePermissionConfigRepository extends JpaRepository<RolePermissionConfig, UUID> {
    @org.springframework.data.jpa.repository.Query("SELECT r FROM RolePermissionConfig r WHERE r.tenant.id = :tenantId AND r.role = :role")
    Optional<RolePermissionConfig> findByTenantIdAndRole(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId, @org.springframework.data.repository.query.Param("role") User.Role role);
}
