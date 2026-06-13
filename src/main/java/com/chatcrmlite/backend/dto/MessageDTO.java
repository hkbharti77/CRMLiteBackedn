package com.chatcrmlite.backend.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class MessageDTO {
    private UUID id;
    private String waMessageId;
    private List<String> tags;
    private UUID contactId;
    private String contactName;
    private String content;
    private String direction; // INCOMING or OUTGOING
    private LocalDateTime timestamp;

    public MessageDTO() {}

    public MessageDTO(UUID id, String waMessageId, List<String> tags, UUID contactId, String contactName, 
                     String content, String direction, LocalDateTime timestamp) {
        this.id = id;
        this.waMessageId = waMessageId;
        this.tags = tags;
        this.contactId = contactId;
        this.contactName = contactName;
        this.content = content;
        this.direction = direction;
        this.timestamp = timestamp;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getWaMessageId() { return waMessageId; }
    public void setWaMessageId(String waMessageId) { this.waMessageId = waMessageId; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public static MessageDTOBuilder builder() {
        return new MessageDTOBuilder();
    }

    public static class MessageDTOBuilder {
        private UUID id;
        private String waMessageId;
        private List<String> tags;
        private UUID contactId;
        private String contactName;
        private String content;
        private String direction;
        private LocalDateTime timestamp;

        public MessageDTOBuilder id(UUID id) { this.id = id; return this; }
        public MessageDTOBuilder waMessageId(String waMessageId) { this.waMessageId = waMessageId; return this; }
        public MessageDTOBuilder tags(List<String> tags) { this.tags = tags; return this; }
        public MessageDTOBuilder contactId(UUID contactId) { this.contactId = contactId; return this; }
        public MessageDTOBuilder contactName(String contactName) { this.contactName = contactName; return this; }
        public MessageDTOBuilder content(String content) { this.content = content; return this; }
        public MessageDTOBuilder direction(String direction) { this.direction = direction; return this; }
        public MessageDTOBuilder timestamp(LocalDateTime timestamp) { this.timestamp = timestamp; return this; }

        public MessageDTO build() {
            return new MessageDTO(id, waMessageId, tags, contactId, contactName, content, direction, timestamp);
        }
    }
}
