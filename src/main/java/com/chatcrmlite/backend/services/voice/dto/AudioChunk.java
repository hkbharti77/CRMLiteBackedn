package com.chatcrmlite.backend.services.voice.dto;

/**
 * Represents a continuous stream of small audio packets for real-time WebSocket communication.
 */
public record AudioChunk(
    byte[] data,
    String encoding,
    int sampleRate,
    int channels,
    long sequenceNumber,
    boolean finalChunk
) {}
