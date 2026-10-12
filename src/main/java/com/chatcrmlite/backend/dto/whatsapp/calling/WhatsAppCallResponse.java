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
public class WhatsAppCallResponse {
    private String callId;
    private String status;
    private String direction;
    private String phoneNumberId;
    private String toWaId;
    private String fromWaId;
    private Instant createdAt;
}
