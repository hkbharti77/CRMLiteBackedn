package com.chatcrmlite.backend.repositories.voice;

import com.chatcrmlite.backend.models.voice.VoiceAssistantConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VoiceAssistantConfigRepository extends JpaRepository<VoiceAssistantConfig, UUID> {
    @org.springframework.data.jpa.repository.Query("SELECT v FROM VoiceAssistantConfig v WHERE v.tenant.id = :tenantId")
    Optional<VoiceAssistantConfig> findByTenantId(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
}
