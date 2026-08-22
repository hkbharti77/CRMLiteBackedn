package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "chat_messages", indexes = {
    @Index(name = "idx_chat_msg_contact_time", columnList = "contact_id, timestamp"),
    @Index(name = "idx_chat_msg_contact", columnList = "contact_id")
})
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

    public enum Sentiment {
        POSITIVE, NEUTRAL, FRUSTRATED, URGENT
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "sentiment")
    private Sentiment sentiment = Sentiment.NEUTRAL;

    @Column(name = "sentiment_score")
    private Double sentimentScore = 0.0;

    @Column(name = "media_url", length = 1000)
    private String mediaUrl;

    @Column(name = "media_type", length = 50)
    private String mediaType;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "media_id", length = 255)
    private String mediaId;

    @Column(name = "thumbnail_url", length = 1000)
    private String thumbnailUrl;

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
    public Sentiment getSentiment() { return sentiment != null ? sentiment : Sentiment.NEUTRAL; }
    public Double getSentimentScore() { return sentimentScore != null ? sentimentScore : 0.0; }

    public void setSentiment(Sentiment sentiment) { this.sentiment = sentiment; }
    public void setSentimentScore(Double sentimentScore) { this.sentimentScore = sentimentScore; }

    public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(String mediaUrl) { this.mediaUrl = mediaUrl; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

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
        private Sentiment sentiment = Sentiment.NEUTRAL;
        private Double sentimentScore = 0.0;
        private String mediaUrl;
        private String mediaType;
        private String mimeType;
        private String fileName;
        private Long fileSize;
        private String mediaId;
        private String thumbnailUrl;

        public MessageBuilder id(UUID id) { this.id = id; return this; }
        public MessageBuilder waMessageId(String waMessageId) { this.waMessageId = waMessageId; return this; }
        public MessageBuilder tags(List<String> tags) { this.tags = tags; return this; }
        public MessageBuilder contact(Contact contact) { this.contact = contact; return this; }
        public MessageBuilder owner(User owner) { this.owner = owner; return this; }
        public MessageBuilder content(String content) { this.content = content; return this; }
        public MessageBuilder direction(Direction direction) { this.direction = direction; return this; }
        public MessageBuilder timestamp(LocalDateTime timestamp) { this.timestamp = timestamp; return this; }
        public MessageBuilder sentiment(Sentiment sentiment) { this.sentiment = sentiment; return this; }
        public MessageBuilder sentimentScore(Double sentimentScore) { this.sentimentScore = sentimentScore; return this; }
        public MessageBuilder mediaUrl(String mediaUrl) { this.mediaUrl = mediaUrl; return this; }
        public MessageBuilder mediaType(String mediaType) { this.mediaType = mediaType; return this; }
        public MessageBuilder mimeType(String mimeType) { this.mimeType = mimeType; return this; }
        public MessageBuilder fileName(String fileName) { this.fileName = fileName; return this; }
        public MessageBuilder fileSize(Long fileSize) { this.fileSize = fileSize; return this; }
        public MessageBuilder mediaId(String mediaId) { this.mediaId = mediaId; return this; }
        public MessageBuilder thumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; return this; }

        public Message build() {
            Message msg = new Message(id, waMessageId, tags, contact, owner, content, direction, timestamp);
            msg.setSentiment(this.sentiment);
            msg.setSentimentScore(this.sentimentScore);
            msg.setMediaUrl(this.mediaUrl);
            msg.setMediaType(this.mediaType);
            msg.setMimeType(this.mimeType);
            msg.setFileName(this.fileName);
            msg.setFileSize(this.fileSize);
            msg.setMediaId(this.mediaId);
            msg.setThumbnailUrl(this.thumbnailUrl);
            return msg;
        }
    }
}
