package com.chatcrmlite.backend.services.voice.state;

public enum CallState {
    IDLE,
    LISTENING,
    SPEECH_DETECTED,
    TRANSCRIBING,
    PROCESSING,
    EXECUTING_TOOL,
    SPEAKING,
    INTERRUPTED,
    TERMINATING,
    TERMINATED
}
