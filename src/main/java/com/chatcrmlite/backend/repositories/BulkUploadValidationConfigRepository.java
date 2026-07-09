package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.BulkUploadValidationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BulkUploadValidationConfigRepository extends JpaRepository<BulkUploadValidationConfig, UUID> {

    Optional<BulkUploadValidationConfig> findByTenantId(UUID tenantId);
}
