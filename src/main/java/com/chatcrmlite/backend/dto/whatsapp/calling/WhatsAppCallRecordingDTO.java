package com.chatcrmlite.backend.dto.whatsapp.calling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppCallRecordingDTO {
    private String callId;
    private boolean available;
    private String status; // AVAILABLE, PROCESSING, FAILED, NOT_AVAILABLE
    private String publicId;
    private String format; // mp3
    private Integer durationSeconds;
    private Long sizeBytes;
    private String playbackUrl; // backend stream URL with short-lived security token
    private String signedDirectUrl; // secure signed CDN URL
    private String downloadUrl; // secure attachment download URL
    private String errorMessage;
    private Instant createdAt;
}
