package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.BroadcastUploadFilterConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BroadcastUploadFilterConfigRepository extends JpaRepository<BroadcastUploadFilterConfig, UUID> {

    Optional<BroadcastUploadFilterConfig> findByTenantId(UUID tenantId);
}
