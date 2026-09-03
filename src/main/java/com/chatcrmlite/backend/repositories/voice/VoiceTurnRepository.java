package com.chatcrmlite.backend.repositories.voice;

import com.chatcrmlite.backend.models.voice.VoiceTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VoiceTurnRepository extends JpaRepository<VoiceTurn, UUID> {
    List<VoiceTurn> findBySessionIdOrderByTurnNumberAsc(UUID sessionId);
    
    // For Conversation Memory (Recent Turns)
    List<VoiceTurn> findTop50BySessionIdOrderByTurnNumberDesc(UUID sessionId);
}
