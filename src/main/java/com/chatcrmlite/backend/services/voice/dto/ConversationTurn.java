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

    public String getTurnId() { return turnId; }
    public long getCreatedAt() { return createdAt; }
    public String getUserTranscript() { return userTranscript; }
    public void setUserTranscript(String userTranscript) { this.userTranscript = userTranscript; }
    public AtomicBoolean getCancellationToken() { return cancellationToken; }
    public void setCancellationToken(AtomicBoolean cancellationToken) { this.cancellationToken = cancellationToken; }

    public void cancel() {
        this.cancellationToken.set(true);
    }

    public boolean isCancelled() {
        return this.cancellationToken.get();
    }
}
