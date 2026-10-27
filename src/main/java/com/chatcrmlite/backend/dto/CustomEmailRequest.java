package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.CustomEmail.RecipientMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CustomEmailRequest {

    @Size(max = 255)
    private String name;

    @NotBlank(message = "Subject is required")
    @Size(max = 255)
    private String subject;

    @NotBlank(message = "Body is required")
    private String body;

    private String ctaLabel;
    private String ctaUrl;

    private RecipientMode recipientMode = RecipientMode.ALL;

    private String tagsFilter;

    private String manualRecipients;

    private java.time.LocalDateTime scheduledAt;

    public CustomEmailRequest() {}

    public CustomEmailRequest(String name, String subject, String body, String ctaLabel, String ctaUrl, RecipientMode recipientMode, String tagsFilter, String manualRecipients, java.time.LocalDateTime scheduledAt) {
        this.name = name;
        this.subject = subject;
        this.body = body;
        this.ctaLabel = ctaLabel;
        this.ctaUrl = ctaUrl;
        this.recipientMode = recipientMode != null ? recipientMode : RecipientMode.ALL;
        this.tagsFilter = tagsFilter;
        this.manualRecipients = manualRecipients;
        this.scheduledAt = scheduledAt;
    }

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
    public RecipientMode getRecipientMode() { return recipientMode; }
    public void setRecipientMode(RecipientMode recipientMode) { this.recipientMode = recipientMode; }
    public String getTagsFilter() { return tagsFilter; }
    public void setTagsFilter(String tagsFilter) { this.tagsFilter = tagsFilter; }
    public String getManualRecipients() { return manualRecipients; }
    public void setManualRecipients(String manualRecipients) { this.manualRecipients = manualRecipients; }
    public java.time.LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(java.time.LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }

    private boolean isAbTest = false;
    private Integer abTestPercentage;
    private String abWinnerMetric;
    private Integer abEvaluationHours;
    private String builderJson;
    private java.util.List<EmailVariantInput> variants;

    public boolean isAbTest() { return isAbTest; }
    public boolean getIsAbTest() { return isAbTest; }
    public void setAbTest(boolean abTest) { isAbTest = abTest; }
    public void setIsAbTest(boolean abTest) { this.isAbTest = abTest; }
    public Integer getAbTestPercentage() { return abTestPercentage; }
    public void setAbTestPercentage(Integer abTestPercentage) { this.abTestPercentage = abTestPercentage; }
    public String getAbWinnerMetric() { return abWinnerMetric; }
    public void setAbWinnerMetric(String abWinnerMetric) { this.abWinnerMetric = abWinnerMetric; }
    public Integer getAbEvaluationHours() { return abEvaluationHours; }
    public void setAbEvaluationHours(Integer abEvaluationHours) { this.abEvaluationHours = abEvaluationHours; }
    public String getBuilderJson() { return builderJson; }
    public void setBuilderJson(String builderJson) { this.builderJson = builderJson; }
    public java.util.List<EmailVariantInput> getVariants() { return variants; }
    public void setVariants(java.util.List<EmailVariantInput> variants) { this.variants = variants; }
}
