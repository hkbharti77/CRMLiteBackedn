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

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(value = """
        INSERT INTO voice_usages (id, business_id, usage_date, stt_seconds_total, tts_characters_total, request_count, estimated_cost_usd, updated_at)
        VALUES (gen_random_uuid(), :businessId, :usageDate, :sttSecs, :ttsChars, 1, 0, NOW())
        ON CONFLICT (business_id, usage_date)
        DO UPDATE SET
            stt_seconds_total = voice_usages.stt_seconds_total + EXCLUDED.stt_seconds_total,
            tts_characters_total = voice_usages.tts_characters_total + EXCLUDED.tts_characters_total,
            request_count = voice_usages.request_count + 1,
            updated_at = NOW()
        """, nativeQuery = true)
    int upsertUsage(@org.springframework.data.repository.query.Param("businessId") UUID businessId,
                    @org.springframework.data.repository.query.Param("usageDate") LocalDate usageDate,
                    @org.springframework.data.repository.query.Param("sttSecs") int sttSecs,
                    @org.springframework.data.repository.query.Param("ttsChars") int ttsChars);
}
