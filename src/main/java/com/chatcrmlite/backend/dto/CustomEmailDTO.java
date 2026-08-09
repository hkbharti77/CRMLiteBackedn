package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.CustomEmail;
import java.time.LocalDateTime;
import java.util.UUID;

public class CustomEmailDTO {

    private UUID id;
    private String name;
    private String subject;
    private String body;
    private String ctaLabel;
    private String ctaUrl;
    private CustomEmail.RecipientMode recipientMode;
    private String tagsFilter;
    private CustomEmail.EmailStatus status;
    private LocalDateTime sentAt;
    
    // New Phase 2 Fields
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime pausedAt;
    private LocalDateTime cancelledAt;
    private int totalRecipients;
    private int processedRecipients;

    private int totalSent;
    private int totalFailed;
    
    // Analytics Metrics
    private long uniqueOpens;
    private long uniqueClicks;
    private long bounces;
    private long unsubscribes;
    private double openRate;
    private double clickRate;
    private double clickToOpenRate;
    private double bounceRate;
    private double unsubscribeRate;

    private LocalDateTime createdAt;

    public CustomEmailDTO() {}

    public CustomEmailDTO(UUID id, String name, String subject, String body, String ctaLabel, String ctaUrl, 
                         CustomEmail.RecipientMode recipientMode, String tagsFilter, CustomEmail.EmailStatus status, 
                         LocalDateTime sentAt, LocalDateTime scheduledAt, LocalDateTime startedAt, LocalDateTime completedAt,
                         LocalDateTime pausedAt, LocalDateTime cancelledAt, int totalRecipients, int processedRecipients,
                         int totalSent, int totalFailed, 
                         long uniqueOpens, long uniqueClicks, long bounces, long unsubscribes,
                         double openRate, double clickRate, double clickToOpenRate, double bounceRate, double unsubscribeRate,
                         LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.subject = subject;
        this.body = body;
        this.ctaLabel = ctaLabel;
        this.ctaUrl = ctaUrl;
        this.recipientMode = recipientMode;
        this.tagsFilter = tagsFilter;
        this.status = status;
        this.sentAt = sentAt;
        this.scheduledAt = scheduledAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.pausedAt = pausedAt;
        this.cancelledAt = cancelledAt;
        this.totalRecipients = totalRecipients;
        this.processedRecipients = processedRecipients;
        this.totalSent = totalSent;
        this.totalFailed = totalFailed;
        this.uniqueOpens = uniqueOpens;
        this.uniqueClicks = uniqueClicks;
        this.bounces = bounces;
        this.unsubscribes = unsubscribes;
        this.openRate = openRate;
        this.clickRate = clickRate;
        this.clickToOpenRate = clickToOpenRate;
        this.bounceRate = bounceRate;
        this.unsubscribeRate = unsubscribeRate;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
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
    public int getTotalRecipients() { return totalRecipients; }
    public void setTotalRecipients(int totalRecipients) { this.totalRecipients = totalRecipients; }
    public int getProcessedRecipients() { return processedRecipients; }
    public void setProcessedRecipients(int processedRecipients) { this.processedRecipients = processedRecipients; }

    public int getTotalSent() { return totalSent; }
    public void setTotalSent(int totalSent) { this.totalSent = totalSent; }
    public int getTotalFailed() { return totalFailed; }
    public void setTotalFailed(int totalFailed) { this.totalFailed = totalFailed; }

    public long getUniqueOpens() { return uniqueOpens; }
    public void setUniqueOpens(long uniqueOpens) { this.uniqueOpens = uniqueOpens; }
    public long getUniqueClicks() { return uniqueClicks; }
    public void setUniqueClicks(long uniqueClicks) { this.uniqueClicks = uniqueClicks; }
    public long getBounces() { return bounces; }
    public void setBounces(long bounces) { this.bounces = bounces; }
    public long getUnsubscribes() { return unsubscribes; }
    public void setUnsubscribes(long unsubscribes) { this.unsubscribes = unsubscribes; }
    public double getOpenRate() { return openRate; }
    public void setOpenRate(double openRate) { this.openRate = openRate; }
    public double getClickRate() { return clickRate; }
    public void setClickRate(double clickRate) { this.clickRate = clickRate; }
    public double getClickToOpenRate() { return clickToOpenRate; }
    public void setClickToOpenRate(double clickToOpenRate) { this.clickToOpenRate = clickToOpenRate; }
    public double getBounceRate() { return bounceRate; }
    public void setBounceRate(double bounceRate) { this.bounceRate = bounceRate; }
    public double getUnsubscribeRate() { return unsubscribeRate; }
    public void setUnsubscribeRate(double unsubscribeRate) { this.unsubscribeRate = unsubscribeRate; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static CustomEmailDTOBuilder builder() {
        return new CustomEmailDTOBuilder();
    }

    public static class CustomEmailDTOBuilder {
        private UUID id;
        private String name;
        private String subject;
        private String body;
        private String ctaLabel;
        private String ctaUrl;
        private CustomEmail.RecipientMode recipientMode;
        private String tagsFilter;
        private CustomEmail.EmailStatus status;
        private LocalDateTime sentAt;
        private LocalDateTime scheduledAt;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private LocalDateTime pausedAt;
        private LocalDateTime cancelledAt;
        private int totalRecipients;
        private int processedRecipients;
        private int totalSent;
        private int totalFailed;
        private long uniqueOpens;
        private long uniqueClicks;
        private long bounces;
        private long unsubscribes;
        private double openRate;
        private double clickRate;
        private double clickToOpenRate;
        private double bounceRate;
        private double unsubscribeRate;
        private LocalDateTime createdAt;

        public CustomEmailDTOBuilder id(UUID id) { this.id = id; return this; }
        public CustomEmailDTOBuilder name(String name) { this.name = name; return this; }
        public CustomEmailDTOBuilder subject(String subject) { this.subject = subject; return this; }
        public CustomEmailDTOBuilder body(String body) { this.body = body; return this; }
        public CustomEmailDTOBuilder ctaLabel(String ctaLabel) { this.ctaLabel = ctaLabel; return this; }
        public CustomEmailDTOBuilder ctaUrl(String ctaUrl) { this.ctaUrl = ctaUrl; return this; }
        public CustomEmailDTOBuilder recipientMode(CustomEmail.RecipientMode recipientMode) { this.recipientMode = recipientMode; return this; }
        public CustomEmailDTOBuilder tagsFilter(String tagsFilter) { this.tagsFilter = tagsFilter; return this; }
        public CustomEmailDTOBuilder status(CustomEmail.EmailStatus status) { this.status = status; return this; }
        public CustomEmailDTOBuilder sentAt(LocalDateTime sentAt) { this.sentAt = sentAt; return this; }
        public CustomEmailDTOBuilder scheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; return this; }
        public CustomEmailDTOBuilder startedAt(LocalDateTime startedAt) { this.startedAt = startedAt; return this; }
        public CustomEmailDTOBuilder completedAt(LocalDateTime completedAt) { this.completedAt = completedAt; return this; }
        public CustomEmailDTOBuilder pausedAt(LocalDateTime pausedAt) { this.pausedAt = pausedAt; return this; }
        public CustomEmailDTOBuilder cancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; return this; }
        public CustomEmailDTOBuilder totalRecipients(int totalRecipients) { this.totalRecipients = totalRecipients; return this; }
        public CustomEmailDTOBuilder processedRecipients(int processedRecipients) { this.processedRecipients = processedRecipients; return this; }
        public CustomEmailDTOBuilder totalSent(int totalSent) { this.totalSent = totalSent; return this; }
        public CustomEmailDTOBuilder totalFailed(int totalFailed) { this.totalFailed = totalFailed; return this; }
        public CustomEmailDTOBuilder uniqueOpens(long uniqueOpens) { this.uniqueOpens = uniqueOpens; return this; }
        public CustomEmailDTOBuilder uniqueClicks(long uniqueClicks) { this.uniqueClicks = uniqueClicks; return this; }
        public CustomEmailDTOBuilder bounces(long bounces) { this.bounces = bounces; return this; }
        public CustomEmailDTOBuilder unsubscribes(long unsubscribes) { this.unsubscribes = unsubscribes; return this; }
        public CustomEmailDTOBuilder openRate(double openRate) { this.openRate = openRate; return this; }
        public CustomEmailDTOBuilder clickRate(double clickRate) { this.clickRate = clickRate; return this; }
        public CustomEmailDTOBuilder clickToOpenRate(double clickToOpenRate) { this.clickToOpenRate = clickToOpenRate; return this; }
        public CustomEmailDTOBuilder bounceRate(double bounceRate) { this.bounceRate = bounceRate; return this; }
        public CustomEmailDTOBuilder unsubscribeRate(double unsubscribeRate) { this.unsubscribeRate = unsubscribeRate; return this; }
        public CustomEmailDTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public CustomEmailDTO build() {
            return new CustomEmailDTO(id, name, subject, body, ctaLabel, ctaUrl, recipientMode, tagsFilter, status, 
                                      sentAt, scheduledAt, startedAt, completedAt, pausedAt, cancelledAt, totalRecipients, processedRecipients,
                                      totalSent, totalFailed, uniqueOpens, uniqueClicks, bounces, unsubscribes, openRate, clickRate, clickToOpenRate, bounceRate, unsubscribeRate, createdAt);
        }
    }
}
