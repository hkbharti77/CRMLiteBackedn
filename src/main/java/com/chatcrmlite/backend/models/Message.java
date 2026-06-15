package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "chat_messages")
public class Message extends BaseTenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String waMessageId; // ID from Meta

    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> tags = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false)
    private Contact contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User owner;

    @Column(columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    private LocalDateTime timestamp;

    public enum Direction {
        INCOMING, OUTGOING
    }

    public Message() {}

    public Message(UUID id, String waMessageId, List<String> tags, Contact contact, User owner, String content, Direction direction, LocalDateTime timestamp) {
        this.id = id;
        this.waMessageId = waMessageId;
        this.tags = (tags != null) ? tags : new ArrayList<>();
        this.contact = contact;
        this.owner = owner;
        this.content = content;
        this.direction = direction;
        this.timestamp = timestamp;
    }

    public UUID getId() { return id; }
    public String getWaMessageId() { return waMessageId; }
    public List<String> getTags() { return tags; }
    public Contact getContact() { return contact; }
    public User getOwner() { return owner; }
    public String getContent() { return content; }
    public Direction getDirection() { return direction; }
    public LocalDateTime getTimestamp() { return timestamp; }

    public void setId(UUID id) { this.id = id; }
    public void setWaMessageId(String waMessageId) { this.waMessageId = waMessageId; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public void setContact(Contact contact) { this.contact = contact; }
    public void setOwner(User owner) { this.owner = owner; }
    public void setContent(String content) { this.content = content; }
    public void setDirection(Direction direction) { this.direction = direction; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }

    public static MessageBuilder builder() { return new MessageBuilder(); }

    public static class MessageBuilder {
        private UUID id;
        private String waMessageId;
        private List<String> tags;
        private Contact contact;
        private User owner;
        private String content;
        private Direction direction;
        private LocalDateTime timestamp;

        public MessageBuilder id(UUID id) { this.id = id; return this; }
        public MessageBuilder waMessageId(String waMessageId) { this.waMessageId = waMessageId; return this; }
        public MessageBuilder tags(List<String> tags) { this.tags = tags; return this; }
        public MessageBuilder contact(Contact contact) { this.contact = contact; return this; }
        public MessageBuilder owner(User owner) { this.owner = owner; return this; }
        public MessageBuilder content(String content) { this.content = content; return this; }
        public MessageBuilder direction(Direction direction) { this.direction = direction; return this; }
        public MessageBuilder timestamp(LocalDateTime timestamp) { this.timestamp = timestamp; return this; }

        public Message build() {
            return new Message(id, waMessageId, tags, contact, owner, content, direction, timestamp);
        }
    }
}
