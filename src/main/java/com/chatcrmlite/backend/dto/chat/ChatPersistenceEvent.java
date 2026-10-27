package com.chatcrmlite.backend.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatPersistenceEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private String eventId;
    private UUID ownerId;
    private String sessionId;
    private String sender; // USER or BOT
    private String content;
    private String source; // FAQ, RAG, MENU, GUARDRAIL
    private String idempotencyKey;
    private long timestamp;
}
