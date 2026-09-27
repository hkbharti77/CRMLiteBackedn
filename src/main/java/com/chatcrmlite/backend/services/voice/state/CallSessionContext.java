package com.chatcrmlite.backend.services.voice.state;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Data
@Slf4j
public class CallSessionContext {
    private final String callId;
    private final AtomicReference<CallState> state = new AtomicReference<>(CallState.IDLE);
    
    // Manage only ONE active turn per call
    private final AtomicReference<TurnContext> activeTurn = new AtomicReference<>();
    private final AtomicInteger turnCounter = new AtomicInteger(0);

    public CallSessionContext(String callId) {
        this.callId = callId;
    }

    public void transitionTo(CallState newState) {
        CallState oldState = state.getAndSet(newState);
        if (oldState != newState) {
            log.info("[VOICE] callId={} state={} event=STATE_TRANSITION oldState={}", callId, newState, oldState);
        }
    }
    
    public TurnContext startNewTurn() {
        TurnContext oldTurn = activeTurn.get();
        if (oldTurn != null) {
            oldTurn.cancel();
        }
        TurnContext newTurn = new TurnContext(callId, turnCounter.incrementAndGet());
        activeTurn.set(newTurn);
        return newTurn;
    }

    public void terminate() {
        transitionTo(CallState.TERMINATING);
        TurnContext currentTurn = activeTurn.getAndSet(null);
        if (currentTurn != null) {
            currentTurn.cancel();
        }
        transitionTo(CallState.TERMINATED);
        log.info("[VOICE] callId={} event=CALL_TERMINATED", callId);
    }
    
    public TurnContext getActiveTurn() {
        return activeTurn.get();
    }
}
