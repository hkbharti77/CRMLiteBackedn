package com.chatcrmlite.backend.dto.whatsapp.calling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppCallTurnDTO {
    private UUID turnId;
    private Integer turnNumber;
    private String speaker; // USER, ASSISTANT
    private String transcript;
    private LocalDateTime timestamp;
    private Double audioDurationSeconds;
    private Integer sttLatencyMs;
    private Integer llmLatencyMs;
    private Integer ttsLatencyMs;
    private Integer ttfaMs;
    private Boolean wasInterrupted;
    private String detectedLanguage;
}
