package com.chatcrmlite.backend.services.voice.dto;

import java.util.Base64;

/**
 * Protocol adapter for decoding/encoding Exotel audio payloads.
 * Usually Exotel streams 8kHz mulaw or 8kHz PCM (L16).
 */
public class ExotelAudioCodec {

    // Default configuration for Exotel AgentStream
    public static final String ENCODING = "mulaw";
    public static final int SAMPLE_RATE = 8000;
    public static final int CHANNELS = 1;

    public static AudioChunk decodeBase64ToChunk(String base64Payload, long sequenceNumber) {
        if (base64Payload == null || base64Payload.isBlank()) {
            return null;
        }
        byte[] decoded = Base64.getDecoder().decode(base64Payload);
        return new AudioChunk(decoded, ENCODING, SAMPLE_RATE, CHANNELS, sequenceNumber, false);
    }

    public static String encodeChunkToBase64(AudioChunk chunk) {
        if (chunk == null || chunk.data() == null) {
            return "";
        }
        return Base64.getEncoder().encodeToString(chunk.data());
    }
}
