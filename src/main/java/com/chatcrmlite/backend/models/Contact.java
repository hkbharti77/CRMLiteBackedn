package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Entity
@Table(name = "contacts", uniqueConstraints = @UniqueConstraint(
        name = "uk_contact_waid_owner",
        columnNames = {"wa_id", "owner_id"}
))
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Contact extends BaseTenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String waId; 

    private String displayId;

    private String name;

    private String email;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "contact_tags",
        joinColumns = @JoinColumn(name = "contact_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    @com.fasterxml.jackson.annotation.JsonIgnore
    private List<Tag> tags = new ArrayList<>();

    private String source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User owner;

    @Column(name = "bot_paused", nullable = false)
    private boolean botPaused = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "latest_sentiment")
    private Message.Sentiment latestSentiment = Message.Sentiment.NEUTRAL;

    @Column(name = "escalated", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean escalated = false;

    @Column(name = "escalated_at")
    private java.time.LocalDateTime escalatedAt;

    public Contact() {}

    public Contact(UUID id, String waId, String displayId, String name, String email, List<Tag> tags, String source, User owner, boolean botPaused) {
        this.id = id;
        this.waId = waId;
        this.displayId = displayId;
        this.name = name;
        this.email = email;
        this.tags = (tags != null) ? tags : new ArrayList<>();
        this.source = source;
        this.owner = owner;
        this.botPaused = botPaused;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getWaId() { return waId; }
    public void setWaId(String waId) { this.waId = waId; }
    public String getDisplayId() { return displayId; }
    public void setDisplayId(String displayId) { this.displayId = displayId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public boolean isBotPaused() { return botPaused; }
    public void setBotPaused(boolean botPaused) { this.botPaused = botPaused; }

    public Message.Sentiment getLatestSentiment() { return latestSentiment != null ? latestSentiment : Message.Sentiment.NEUTRAL; }
    public void setLatestSentiment(Message.Sentiment latestSentiment) { this.latestSentiment = latestSentiment; }
    public boolean isEscalated() { return escalated; }
    public void setEscalated(boolean escalated) { this.escalated = escalated; }
    public java.time.LocalDateTime getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(java.time.LocalDateTime escalatedAt) { this.escalatedAt = escalatedAt; }

    public String getPhone() { return waId; }
    public Boolean getOptedOut() { return false; }
    public Boolean getBlacklisted() { return false; }
    public java.util.Map<String, Object> getCustomFields() { return java.util.Collections.emptyMap(); }
    public java.time.LocalDateTime getLastInteractiveAt() { return null; }

    @PrePersist
    @PreUpdate
    @Override
    protected void populateTenant() {
        super.populateTenant();
        if (this.displayId == null) {
            String prefix = "CON";
            if (this.getTenant() != null && this.getTenant().getBusinessName() != null) {
                String bizName = this.getTenant().getBusinessName().replaceAll("[^a-zA-Z0-9]", "");
                if (bizName.length() >= 3) {
                    prefix = bizName.substring(0, 3).toUpperCase();
                } else if (!bizName.isEmpty()) {
                    prefix = bizName.toUpperCase();
                }
            }
            String randomStr = UUID.randomUUID().toString().substring(0, 5).toUpperCase();
            this.displayId = prefix + "-" + randomStr;
        }
    }

    public static ContactBuilder builder() { return new ContactBuilder(); }

    public static class ContactBuilder {
        private UUID id;
        private String waId;
        private String displayId;
        private String name;
        private String email;
        private List<Tag> tags = new ArrayList<>();
        private String source;
        private User owner;
        private boolean botPaused;

        public ContactBuilder id(UUID id) { this.id = id; return this; }
        public ContactBuilder waId(String waId) { this.waId = waId; return this; }
        public ContactBuilder displayId(String displayId) { this.displayId = displayId; return this; }
        public ContactBuilder name(String name) { this.name = name; return this; }
        public ContactBuilder email(String email) { this.email = email; return this; }
        public ContactBuilder tags(List<Tag> tags) { this.tags = tags; return this; }
        public ContactBuilder source(String source) { this.source = source; return this; }
        public ContactBuilder owner(User owner) { this.owner = owner; return this; }
        public ContactBuilder botPaused(boolean botPaused) { this.botPaused = botPaused; return this; }

        public Contact build() {
            return new Contact(id, waId, displayId, name, email, tags, source, owner, botPaused);
        }
    }
}
