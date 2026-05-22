package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.CustomEmail;
import java.time.LocalDateTime;
import java.util.UUID;

public class CustomEmailDTO {

    private UUID id;
    private String subject;
    private String body;
    private String ctaLabel;
    private String ctaUrl;
    private CustomEmail.RecipientMode recipientMode;
    private String tagsFilter;
    private CustomEmail.EmailStatus status;
    private LocalDateTime sentAt;
    private int totalSent;
    private int totalFailed;
    private LocalDateTime createdAt;

    public CustomEmailDTO() {}

    public CustomEmailDTO(UUID id, String subject, String body, String ctaLabel, String ctaUrl, 
                         CustomEmail.RecipientMode recipientMode, String tagsFilter, CustomEmail.EmailStatus status, 
                         LocalDateTime sentAt, int totalSent, int totalFailed, LocalDateTime createdAt) {
        this.id = id;
        this.subject = subject;
        this.body = body;
        this.ctaLabel = ctaLabel;
        this.ctaUrl = ctaUrl;
        this.recipientMode = recipientMode;
        this.tagsFilter = tagsFilter;
        this.status = status;
        this.sentAt = sentAt;
        this.totalSent = totalSent;
        this.totalFailed = totalFailed;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getCtaLabel() { return ctaLabel; }
    public void setCtaLabel(String ctaLabel) { this.ctaLabel = ctaLabel; }
    public String getCtaUrl() { return ctaUrl; }
    public void setCtaUrl(String ctaUrl) { this.ctaUrl = ctaUrl; }
    public CustomEmail.RecipientMode getRecipientMode() { return recipientMode; }
    public void setRecipientMode(CustomEmail.RecipientMode recipientMode) { this.recipientMode = recipientMode; }
    public String getTagsFilter() { return tagsFilter; }
    public void setTagsFilter(String tagsFilter) { this.tagsFilter = tagsFilter; }
    public CustomEmail.EmailStatus getStatus() { return status; }
    public void setStatus(CustomEmail.EmailStatus status) { this.status = status; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public int getTotalSent() { return totalSent; }
    public void setTotalSent(int totalSent) { this.totalSent = totalSent; }
    public int getTotalFailed() { return totalFailed; }
    public void setTotalFailed(int totalFailed) { this.totalFailed = totalFailed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static CustomEmailDTOBuilder builder() {
        return new CustomEmailDTOBuilder();
    }

    public static class CustomEmailDTOBuilder {
        private UUID id;
        private String subject;
        private String body;
        private String ctaLabel;
        private String ctaUrl;
        private CustomEmail.RecipientMode recipientMode;
        private String tagsFilter;
        private CustomEmail.EmailStatus status;
        private LocalDateTime sentAt;
        private int totalSent;
        private int totalFailed;
        private LocalDateTime createdAt;

        public CustomEmailDTOBuilder id(UUID id) { this.id = id; return this; }
        public CustomEmailDTOBuilder subject(String subject) { this.subject = subject; return this; }
        public CustomEmailDTOBuilder body(String body) { this.body = body; return this; }
        public CustomEmailDTOBuilder ctaLabel(String ctaLabel) { this.ctaLabel = ctaLabel; return this; }
        public CustomEmailDTOBuilder ctaUrl(String ctaUrl) { this.ctaUrl = ctaUrl; return this; }
        public CustomEmailDTOBuilder recipientMode(CustomEmail.RecipientMode recipientMode) { this.recipientMode = recipientMode; return this; }
        public CustomEmailDTOBuilder tagsFilter(String tagsFilter) { this.tagsFilter = tagsFilter; return this; }
        public CustomEmailDTOBuilder status(CustomEmail.EmailStatus status) { this.status = status; return this; }
        public CustomEmailDTOBuilder sentAt(LocalDateTime sentAt) { this.sentAt = sentAt; return this; }
        public CustomEmailDTOBuilder totalSent(int totalSent) { this.totalSent = totalSent; return this; }
        public CustomEmailDTOBuilder totalFailed(int totalFailed) { this.totalFailed = totalFailed; return this; }
        public CustomEmailDTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public CustomEmailDTO build() {
            return new CustomEmailDTO(id, subject, body, ctaLabel, ctaUrl, recipientMode, tagsFilter, status, sentAt, totalSent, totalFailed, createdAt);
        }
    }
}
