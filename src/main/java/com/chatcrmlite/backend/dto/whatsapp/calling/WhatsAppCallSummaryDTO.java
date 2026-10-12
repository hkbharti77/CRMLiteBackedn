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
public class WhatsAppCallSummaryDTO {
    private UUID id;
    private String callId;
    private String direction;
    private String fromWaId;
    private String toWaId;
    private String state;
    private String phoneNumberId;
    private Instant startedAt;
    private Instant connectedAt;
    private Instant endedAt;
    private Integer durationSeconds;
    private String terminationReason;
    private Instant createdAt;
}
