package com.chatcrmlite.backend.repositories.voice;

import com.chatcrmlite.backend.models.voice.VoiceSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VoiceSessionRepository extends JpaRepository<VoiceSession, UUID> {
    List<VoiceSession> findByBusinessIdOrderByStartedAtDesc(UUID businessId);
    Optional<VoiceSession> findByIdAndBusinessId(UUID id, UUID businessId);
    List<VoiceSession> findByVisitorIdAndBusinessId(String visitorId, UUID businessId);
}
