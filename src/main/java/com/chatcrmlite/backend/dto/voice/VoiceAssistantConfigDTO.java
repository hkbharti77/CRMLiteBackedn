package com.chatcrmlite.backend.dto.voice;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoiceAssistantConfigDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID id;

    @Size(max = 50, message = "Assistant name must be at most 50 characters")
    private String assistantName;

    @Size(max = 500, message = "Greeting text must be at most 500 characters")
    private String greetingText;

    @Size(max = 5000, message = "Persona prompt must be at most 5000 characters")
    private String personaPrompt;

    @Size(max = 100, message = "TTS voice ID must be at most 100 characters")
    private String ttsVoiceId; // e.g. "aura-asteria-en" (female) or "aura-arcas-en" (male)

    private Boolean enabled;
    private Long version;
    private LocalDateTime updatedAt;
    private String updatedByEmail;
}
