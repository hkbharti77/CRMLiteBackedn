package com.chatcrmlite.backend.dto.whatsapp.calling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppCallDetailDTO {
    private UUID id;
    private String callId;
    private String direction;
    private String fromWaId;
    private String toWaId;
    private String state;
    private String phoneNumberId;
    private String wabaId;
    private String signalingMode;
    private String mediaMode;
    private String codec;

    private Instant startedAt;
    private Instant acceptedAt;
    private Instant connectedAt;
    private Instant endedAt;
    private Integer durationSeconds;

    private String terminationSource;
    private String terminationReason;
    private String failureStage;

    private Integer metaErrorCode;
    private String metaErrorTitle;
    private String metaErrorMessage;

    private UUID aiSessionId;

    // Contact metadata if available within tenant
    private UUID contactId;
    private String contactName;

    // Call Recording Metadata
    private Boolean recordingAvailable;
    private String recordingStatus;
    private String recordingPublicId;
    private String recordingFormat;
    private Integer recordingDurationSeconds;
    private Long recordingSizeBytes;
    private String recordingPlaybackUrl;
    private String recordingDownloadUrl;

    private Instant createdAt;
    private Instant updatedAt;
}
