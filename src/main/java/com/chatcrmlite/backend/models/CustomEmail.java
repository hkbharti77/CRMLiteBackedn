package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;

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

    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime pausedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime sentAt; // kept for legacy reference

    private int totalRecipients = 0;
    private int processedRecipients = 0;
    private int totalSent = 0;
    private int totalFailed = 0;

    private UUID snapshotId;

    @Version
    private int version;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public CustomEmail() {}

    public CustomEmail(UUID id, User owner, String subject, String body, String ctaLabel, String ctaUrl,
                       RecipientMode recipientMode, String tagsFilter, EmailStatus status,
                       LocalDateTime scheduledAt, LocalDateTime startedAt, LocalDateTime completedAt,
                       LocalDateTime pausedAt, LocalDateTime cancelledAt, LocalDateTime sentAt,
                       int totalRecipients, int processedRecipients, int totalSent, int totalFailed,
                       UUID snapshotId, int version, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.owner = owner;
        this.subject = subject;
        this.body = body;
        this.ctaLabel = ctaLabel;
        this.ctaUrl = ctaUrl;
        this.recipientMode = recipientMode != null ? recipientMode : RecipientMode.ALL;
        this.tagsFilter = tagsFilter;
        this.status = status != null ? status : EmailStatus.DRAFT;
        this.scheduledAt = scheduledAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.pausedAt = pausedAt;
        this.cancelledAt = cancelledAt;
        this.sentAt = sentAt;
        this.totalRecipients = totalRecipients;
        this.processedRecipients = processedRecipients;
        this.totalSent = totalSent;
        this.totalFailed = totalFailed;
        this.snapshotId = snapshotId;
        this.version = version;
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
    
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getPausedAt() { return pausedAt; }
    public void setPausedAt(LocalDateTime pausedAt) { this.pausedAt = pausedAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }

    public int getTotalRecipients() { return totalRecipients; }
    public void setTotalRecipients(int totalRecipients) { this.totalRecipients = totalRecipients; }
    public int getProcessedRecipients() { return processedRecipients; }
    public void setProcessedRecipients(int processedRecipients) { this.processedRecipients = processedRecipients; }
    public int getTotalSent() { return totalSent; }
    public void setTotalSent(int totalSent) { this.totalSent = totalSent; }
    public int getTotalFailed() { return totalFailed; }
    public void setTotalFailed(int totalFailed) { this.totalFailed = totalFailed; }

    public UUID getSnapshotId() { return snapshotId; }
    public void setSnapshotId(UUID snapshotId) { this.snapshotId = snapshotId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    
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
        private LocalDateTime scheduledAt;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private LocalDateTime pausedAt;
        private LocalDateTime cancelledAt;
        private LocalDateTime sentAt;
        private int totalRecipients = 0;
        private int processedRecipients = 0;
        private int totalSent = 0;
        private int totalFailed = 0;
        private UUID snapshotId;
        private int version = 0;
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
        
        public CustomEmailBuilder scheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; return this; }
        public CustomEmailBuilder startedAt(LocalDateTime startedAt) { this.startedAt = startedAt; return this; }
        public CustomEmailBuilder completedAt(LocalDateTime completedAt) { this.completedAt = completedAt; return this; }
        public CustomEmailBuilder pausedAt(LocalDateTime pausedAt) { this.pausedAt = pausedAt; return this; }
        public CustomEmailBuilder cancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; return this; }
        public CustomEmailBuilder sentAt(LocalDateTime sentAt) { this.sentAt = sentAt; return this; }
        
        public CustomEmailBuilder totalRecipients(int totalRecipients) { this.totalRecipients = totalRecipients; return this; }
        public CustomEmailBuilder processedRecipients(int processedRecipients) { this.processedRecipients = processedRecipients; return this; }
        public CustomEmailBuilder totalSent(int totalSent) { this.totalSent = totalSent; return this; }
        public CustomEmailBuilder totalFailed(int totalFailed) { this.totalFailed = totalFailed; return this; }
        public CustomEmailBuilder snapshotId(UUID snapshotId) { this.snapshotId = snapshotId; return this; }
        public CustomEmailBuilder version(int version) { this.version = version; return this; }
        
        public CustomEmailBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public CustomEmailBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public CustomEmail build() {
            return new CustomEmail(id, owner, subject, body, ctaLabel, ctaUrl, recipientMode, tagsFilter, status, 
                                   scheduledAt, startedAt, completedAt, pausedAt, cancelledAt, sentAt, 
                                   totalRecipients, processedRecipients, totalSent, totalFailed, 
                                   snapshotId, version, createdAt, updatedAt);
        }
    }

    public enum RecipientMode { ALL, TAGGED, MANUAL, LEAD_STATUS_BASED, ADVANCED }
    public enum EmailStatus { DRAFT, SCHEDULED, SENDING, PAUSED, CANCELLED, COMPLETED, FAILED, SENT } // SENT kept for backwards compat
}
