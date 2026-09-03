package com.chatcrmlite.backend.services.voice.dto;

import lombok.Data;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Data
public class ConversationTurn {
    private final String turnId;
    private final long createdAt;
    private String userTranscript;
    private AtomicBoolean cancellationToken;

    public ConversationTurn() {
        this.turnId = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.cancellationToken = new AtomicBoolean(false);
    }

    public void cancel() {
        this.cancellationToken.set(true);
    }

    public boolean isCancelled() {
        return this.cancellationToken.get();
    }
}
