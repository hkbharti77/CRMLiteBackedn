package com.chatcrmlite.backend.models.voice;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "voice_turns")
public class VoiceTurn implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @JsonIgnore
    private VoiceSession session;

    @Column(name = "turn_number", nullable = false)
    private Integer turnNumber;

    @Column(name = "user_transcript", columnDefinition = "TEXT")
    private String userTranscript;

    @Column(name = "bot_response_text", columnDefinition = "TEXT")
    private String botResponseText;

    @Column(name = "audio_duration_seconds")
    private Double audioDurationSeconds;

    @Column(name = "stt_latency_ms")
    private Integer sttLatencyMs;

    @Column(name = "llm_latency_ms")
    private Integer llmLatencyMs;

    @Column(name = "tts_latency_ms")
    private Integer ttsLatencyMs;

    @Column(name = "ttfa_ms")
    private Integer ttfaMs;

    @Column(name = "was_interrupted")
    private Boolean wasInterrupted = false;

    @Column(name = "detected_language", length = 20)
    private String detectedLanguage = "en";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.wasInterrupted == null) {
            this.wasInterrupted = false;
        }
    }
}
