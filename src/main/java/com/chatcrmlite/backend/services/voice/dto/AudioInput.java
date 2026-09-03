package com.chatcrmlite.backend.services.voice.dto;

/**
 * Represents a complete audio payload for non-streaming turn-based communication (e.g. from the web widget).
 */
public record AudioInput(
    byte[] data,
    String contentType,
    String language
) {}
