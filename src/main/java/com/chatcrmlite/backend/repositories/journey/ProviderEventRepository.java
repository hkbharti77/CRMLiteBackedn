package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.ProviderEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProviderEventRepository extends JpaRepository<ProviderEvent, UUID> {

    Optional<ProviderEvent> findByBusinessIdAndProviderAndProviderEventId(String businessId, String provider, String providerEventId);
}
