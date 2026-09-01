package com.chatcrmlite.backend.repositories.voice;

import com.chatcrmlite.backend.models.voice.VoiceUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VoiceUsageRepository extends JpaRepository<VoiceUsage, UUID> {
    Optional<VoiceUsage> findByBusinessIdAndUsageDate(UUID businessId, LocalDate usageDate);
    List<VoiceUsage> findByBusinessIdAndUsageDateBetweenOrderByUsageDateAsc(UUID businessId, LocalDate startDate, LocalDate endDate);
}
