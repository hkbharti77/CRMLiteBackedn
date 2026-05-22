package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.Message;
import java.time.LocalDateTime;
import java.util.UUID;

public class MessageDTO {
    private UUID id;
    private String content;
    private Message.Direction direction;
    private LocalDateTime timestamp;
    private String waMessageId;

    public MessageDTO() {}

    public MessageDTO(UUID id, String content, Message.Direction direction, LocalDateTime timestamp, String waMessageId) {
        this.id = id;
        this.content = content;
        this.direction = direction;
        this.timestamp = timestamp;
        this.waMessageId = waMessageId;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Message.Direction getDirection() { return direction; }
    public void setDirection(Message.Direction direction) { this.direction = direction; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public String getWaMessageId() { return waMessageId; }
    public void setWaMessageId(String waMessageId) { this.waMessageId = waMessageId; }

    public static MessageDTOBuilder builder() {
        return new MessageDTOBuilder();
    }

    public static class MessageDTOBuilder {
        private UUID id;
        private String content;
        private Message.Direction direction;
        private LocalDateTime timestamp;
        private String waMessageId;

        public MessageDTOBuilder id(UUID id) { this.id = id; return this; }
        public MessageDTOBuilder content(String content) { this.content = content; return this; }
        public MessageDTOBuilder direction(Message.Direction direction) { this.direction = direction; return this; }
        public MessageDTOBuilder timestamp(LocalDateTime timestamp) { this.timestamp = timestamp; return this; }
        public MessageDTOBuilder waMessageId(String waMessageId) { this.waMessageId = waMessageId; return this; }

        public MessageDTO build() {
            return new MessageDTO(id, content, direction, timestamp, waMessageId);
        }
    }
}
