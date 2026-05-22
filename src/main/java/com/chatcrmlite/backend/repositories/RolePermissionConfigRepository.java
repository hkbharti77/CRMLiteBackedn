package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.RolePermissionConfig;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RolePermissionConfigRepository extends JpaRepository<RolePermissionConfig, UUID> {
    Optional<RolePermissionConfig> findByTenantIdAndRole(UUID tenantId, User.Role role);
}
