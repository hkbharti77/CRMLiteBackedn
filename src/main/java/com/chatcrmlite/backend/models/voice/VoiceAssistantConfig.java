package com.chatcrmlite.backend.models.voice;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "voice_assistant_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class VoiceAssistantConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, unique = true)
    @JsonIgnore
    private Tenant tenant;

    @Builder.Default
    @Column(name = "assistant_name", nullable = false, length = 100)
    private String assistantName = "Assistant";

    @Builder.Default
    @Column(name = "greeting_text", nullable = false, columnDefinition = "TEXT")
    private String greetingText = "Hello! How can I help you today?";

    @Builder.Default
    @Column(name = "persona_prompt", nullable = false, columnDefinition = "TEXT")
    private String personaPrompt = "You are a helpful, professional AI voice assistant.";

    /**
     * Deepgram Aura voice model ID selected by the admin.
     * Examples: "aura-asteria-en" (female), "aura-arcas-en" (male).
     * Defaults to Asteria (female) if not set.
     */
    @Builder.Default
    @Column(name = "tts_voice_id", length = 100)
    private String ttsVoiceId = "aura-asteria-en";

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;

    @Version
    @Builder.Default
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (assistantName == null || assistantName.isBlank()) assistantName = "Assistant";
        if (greetingText == null || greetingText.isBlank()) greetingText = "Hello! How can I help you today?";
        if (personaPrompt == null || personaPrompt.isBlank()) personaPrompt = "You are a helpful, professional AI voice assistant.";
        if (ttsVoiceId == null || ttsVoiceId.isBlank()) ttsVoiceId = "aura-asteria-en";
        if (enabled == null) enabled = true;
        if (version == null) version = 0L;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
