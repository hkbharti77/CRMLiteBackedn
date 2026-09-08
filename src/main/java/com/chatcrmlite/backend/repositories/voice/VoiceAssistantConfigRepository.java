package com.chatcrmlite.backend.repositories.voice;

import com.chatcrmlite.backend.models.voice.VoiceAssistantConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VoiceAssistantConfigRepository extends JpaRepository<VoiceAssistantConfig, UUID> {
    Optional<VoiceAssistantConfig> findByTenantId(UUID tenantId);
}
