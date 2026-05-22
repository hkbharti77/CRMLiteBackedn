package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "custom_emails")
public class CustomEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private String subject;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    private String ctaLabel;
    private String ctaUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_mode", nullable = false)
    private RecipientMode recipientMode = RecipientMode.ALL;

    @Column(name = "tags_filter")
    private String tagsFilter;

    @Enumerated(EnumType.STRING)
    private EmailStatus status = EmailStatus.DRAFT;

    private LocalDateTime sentAt;
    private int totalSent = 0;
    private int totalFailed = 0;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public CustomEmail() {}

    public CustomEmail(UUID id, User owner, String subject, String body, String ctaLabel, String ctaUrl, 
                       RecipientMode recipientMode, String tagsFilter, EmailStatus status, 
                       LocalDateTime sentAt, int totalSent, int totalFailed, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.owner = owner;
        this.subject = subject;
        this.body = body;
        this.ctaLabel = ctaLabel;
        this.ctaUrl = ctaUrl;
        this.recipientMode = recipientMode != null ? recipientMode : RecipientMode.ALL;
        this.tagsFilter = tagsFilter;
        this.status = status != null ? status : EmailStatus.DRAFT;
        this.sentAt = sentAt;
        this.totalSent = totalSent;
        this.totalFailed = totalFailed;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getCtaLabel() { return ctaLabel; }
    public void setCtaLabel(String ctaLabel) { this.ctaLabel = ctaLabel; }
    public String getCtaUrl() { return ctaUrl; }
    public void setCtaUrl(String ctaUrl) { this.ctaUrl = ctaUrl; }
    public RecipientMode getRecipientMode() { return recipientMode; }
    public void setRecipientMode(RecipientMode recipientMode) { this.recipientMode = recipientMode; }
    public String getTagsFilter() { return tagsFilter; }
    public void setTagsFilter(String tagsFilter) { this.tagsFilter = tagsFilter; }
    public EmailStatus getStatus() { return status; }
    public void setStatus(EmailStatus status) { this.status = status; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public int getTotalSent() { return totalSent; }
    public void setTotalSent(int totalSent) { this.totalSent = totalSent; }
    public int getTotalFailed() { return totalFailed; }
    public void setTotalFailed(int totalFailed) { this.totalFailed = totalFailed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static CustomEmailBuilder builder() {
        return new CustomEmailBuilder();
    }

    public static class CustomEmailBuilder {
        private UUID id;
        private User owner;
        private String subject;
        private String body;
        private String ctaLabel;
        private String ctaUrl;
        private RecipientMode recipientMode = RecipientMode.ALL;
        private String tagsFilter;
        private EmailStatus status = EmailStatus.DRAFT;
        private LocalDateTime sentAt;
        private int totalSent = 0;
        private int totalFailed = 0;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt;

        public CustomEmailBuilder id(UUID id) { this.id = id; return this; }
        public CustomEmailBuilder owner(User owner) { this.owner = owner; return this; }
        public CustomEmailBuilder subject(String subject) { this.subject = subject; return this; }
        public CustomEmailBuilder body(String body) { this.body = body; return this; }
        public CustomEmailBuilder ctaLabel(String ctaLabel) { this.ctaLabel = ctaLabel; return this; }
        public CustomEmailBuilder ctaUrl(String ctaUrl) { this.ctaUrl = ctaUrl; return this; }
        public CustomEmailBuilder recipientMode(RecipientMode recipientMode) { this.recipientMode = recipientMode; return this; }
        public CustomEmailBuilder tagsFilter(String tagsFilter) { this.tagsFilter = tagsFilter; return this; }
        public CustomEmailBuilder status(EmailStatus status) { this.status = status; return this; }
        public CustomEmailBuilder sentAt(LocalDateTime sentAt) { this.sentAt = sentAt; return this; }
        public CustomEmailBuilder totalSent(int totalSent) { this.totalSent = totalSent; return this; }
        public CustomEmailBuilder totalFailed(int totalFailed) { this.totalFailed = totalFailed; return this; }
        public CustomEmailBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public CustomEmailBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public CustomEmail build() {
            return new CustomEmail(id, owner, subject, body, ctaLabel, ctaUrl, recipientMode, tagsFilter, status, sentAt, totalSent, totalFailed, createdAt, updatedAt);
        }
    }

    public enum RecipientMode { ALL, TAGGED, MANUAL }
    public enum EmailStatus { DRAFT, SENT, FAILED }
}
