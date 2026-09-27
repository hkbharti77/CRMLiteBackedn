package com.chatcrmlite.backend.services.voice.state;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Data
@Slf4j
public class TurnContext {
    private final String callId;
    private final int turnId;
    private final long startTimestamp;
    
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    
    // Futures for async cancellation
    private CompletableFuture<String> sttFuture;
    private CompletableFuture<String> llmFuture;
    private CompletableFuture<Void> ttsFuture;

    public TurnContext(String callId, int turnId) {
        this.callId = callId;
        this.turnId = turnId;
        this.startTimestamp = System.currentTimeMillis();
    }

    public void cancel() {
        if (isCancelled.compareAndSet(false, true)) {
            log.info("[VOICE] callId={} turnId={} event=TURN_CANCELLED", callId, turnId);
            if (sttFuture != null && !sttFuture.isDone()) sttFuture.cancel(true);
            if (llmFuture != null && !llmFuture.isDone()) llmFuture.cancel(true);
            if (ttsFuture != null && !ttsFuture.isDone()) ttsFuture.cancel(true);
        }
    }

    public boolean isCancelled() {
        return isCancelled.get();
    }

    public void verifyActive() throws CancellationException {
        if (isCancelled()) {
            throw new CancellationException("Turn " + turnId + " was cancelled");
        }
    }
}
