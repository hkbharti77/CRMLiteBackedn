package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.models.voice.VoiceSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoiceTurnConcurrencyTest {

    @Test
    @DisplayName("Barge-In Race Defense: Stale turn must be invalidated when new turn begins")
    void testBargeInStaleTurnInvalidation() {
        VoiceSession session = new VoiceSession();
        session.setActiveTurnNumber(1);

        assertTrue(session.isCurrentTurn(1), "Turn 1 should be active initially");

        // User barges in, bumping active turn number to 2
        session.setActiveTurnNumber(2);

        assertFalse(session.isCurrentTurn(1), "Turn 1 must be marked invalid after barge-in advances to Turn 2");
        assertTrue(session.isCurrentTurn(2), "Turn 2 should now be the current active turn");
    }

    @Test
    @DisplayName("Double Submission Defense: Null or mismatched turn numbers must return false")
    void testTurnNumberGuards() {
        VoiceSession session = new VoiceSession();
        assertFalse(session.isCurrentTurn(1), "Uninitialized turn should return false");

        session.setActiveTurnNumber(5);
        assertFalse(session.isCurrentTurn(4), "Past turn 4 must not match active turn 5");
        assertFalse(session.isCurrentTurn(6), "Future turn 6 must not match active turn 5");
        assertTrue(session.isCurrentTurn(5), "Exact turn 5 must match");
    }
}
