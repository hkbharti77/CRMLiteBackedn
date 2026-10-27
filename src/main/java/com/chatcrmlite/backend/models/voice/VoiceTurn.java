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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public VoiceSession getSession() { return session; }
    public void setSession(VoiceSession session) { this.session = session; }
    public Integer getTurnNumber() { return turnNumber; }
    public void setTurnNumber(Integer turnNumber) { this.turnNumber = turnNumber; }
    public String getUserTranscript() { return userTranscript; }
    public void setUserTranscript(String userTranscript) { this.userTranscript = userTranscript; }
    public String getBotResponseText() { return botResponseText; }
    public void setBotResponseText(String botResponseText) { this.botResponseText = botResponseText; }
    public Double getAudioDurationSeconds() { return audioDurationSeconds; }
    public void setAudioDurationSeconds(Double audioDurationSeconds) { this.audioDurationSeconds = audioDurationSeconds; }
    public Integer getSttLatencyMs() { return sttLatencyMs; }
    public void setSttLatencyMs(Integer sttLatencyMs) { this.sttLatencyMs = sttLatencyMs; }
    public Integer getLlmLatencyMs() { return llmLatencyMs; }
    public void setLlmLatencyMs(Integer llmLatencyMs) { this.llmLatencyMs = llmLatencyMs; }
    public Integer getTtsLatencyMs() { return ttsLatencyMs; }
    public void setTtsLatencyMs(Integer ttsLatencyMs) { this.ttsLatencyMs = ttsLatencyMs; }
    public Integer getTtfaMs() { return ttfaMs; }
    public void setTtfaMs(Integer ttfaMs) { this.ttfaMs = ttfaMs; }
    public Boolean getWasInterrupted() { return wasInterrupted; }
    public void setWasInterrupted(Boolean wasInterrupted) { this.wasInterrupted = wasInterrupted; }
    public String getDetectedLanguage() { return detectedLanguage; }
    public void setDetectedLanguage(String detectedLanguage) { this.detectedLanguage = detectedLanguage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
