package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.CustomEmail.RecipientMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CustomEmailRequest {

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

    public CustomEmailRequest() {}

    public CustomEmailRequest(String subject, String body, String ctaLabel, String ctaUrl, RecipientMode recipientMode, String tagsFilter, String manualRecipients) {
        this.subject = subject;
        this.body = body;
        this.ctaLabel = ctaLabel;
        this.ctaUrl = ctaUrl;
        this.recipientMode = recipientMode != null ? recipientMode : RecipientMode.ALL;
        this.tagsFilter = tagsFilter;
        this.manualRecipients = manualRecipients;
    }

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
}
