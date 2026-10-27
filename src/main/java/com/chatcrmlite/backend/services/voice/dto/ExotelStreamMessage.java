package com.chatcrmlite.backend.services.voice.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;

/**
 * Protocol adapter for Exotel's AgentStream WebSocket messages.
 * Handles multiple field name variants Exotel may send (camelCase / snake_case).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExotelStreamMessage {

    private String event;

    @JsonProperty("stream_sid")
    @JsonAlias({"stream_id", "streamSid"})
    private String streamId;

    @JsonProperty("call_sid")
    @JsonAlias({"call_id", "callSid"})
    private String callId;

    private Media media;
    private Map<String, Object> metadata;

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public String getStreamId() { return streamId; }
    public void setStreamId(String streamId) { this.streamId = streamId; }

    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }

    public Media getMedia() { return media; }
    public void setMedia(Media media) { this.media = media; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Media {
        private String payload; // Base64 encoded audio chunk
        private Integer chunk;  // Sequence number
        private String track;

        public String getPayload() { return payload; }
        public void setPayload(String payload) { this.payload = payload; }

        public Integer getChunk() { return chunk; }
        public void setChunk(Integer chunk) { this.chunk = chunk; }

        public String getTrack() { return track; }
        public void setTrack(String track) { this.track = track; }
    }
}
