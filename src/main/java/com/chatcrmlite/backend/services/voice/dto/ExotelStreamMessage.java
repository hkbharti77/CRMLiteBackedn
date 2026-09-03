package com.chatcrmlite.backend.services.voice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.Map;

/**
 * Protocol adapter for Exotel's AgentStream WebSocket messages.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExotelStreamMessage {
    
    private String event;
    private String streamId;
    private String callId;
    private Media media;
    private Map<String, Object> metadata;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Media {
        private String payload; // Base64 encoded audio chunk
        private int chunk;      // Sequence number
        private String track;
    }
}
