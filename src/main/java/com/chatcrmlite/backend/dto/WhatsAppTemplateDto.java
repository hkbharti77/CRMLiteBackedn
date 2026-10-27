package com.chatcrmlite.backend.dto;

import lombok.*;
import java.util.List;

@Data
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppTemplateDto {
    private String id;
    private String name;
    private String language;
    private String category; // MARKETING, UTILITY, AUTHENTICATION
    private String status; // APPROVED, PENDING, REJECTED, PAUSED, DISABLED

    private String headerType; // NONE, TEXT, IMAGE, VIDEO, DOCUMENT
    private String headerContent;
    private String bodyText;
    private String footerText;
    private String rejectedReason;

    private List<String> headerSampleValues;
    private List<String> bodySampleValues;
    private List<TemplateButtonDto> buttons;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHeaderType() { return headerType; }
    public void setHeaderType(String headerType) { this.headerType = headerType; }
    public String getHeaderContent() { return headerContent; }
    public void setHeaderContent(String headerContent) { this.headerContent = headerContent; }
    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public String getFooterText() { return footerText; }
    public void setFooterText(String footerText) { this.footerText = footerText; }
    public String getRejectedReason() { return rejectedReason; }
    public void setRejectedReason(String rejectedReason) { this.rejectedReason = rejectedReason; }
    public List<String> getHeaderSampleValues() { return headerSampleValues; }
    public void setHeaderSampleValues(List<String> headerSampleValues) { this.headerSampleValues = headerSampleValues; }
    public List<String> getBodySampleValues() { return bodySampleValues; }
    public void setBodySampleValues(List<String> bodySampleValues) { this.bodySampleValues = bodySampleValues; }
    public List<TemplateButtonDto> getButtons() { return buttons; }
    public void setButtons(List<TemplateButtonDto> buttons) { this.buttons = buttons; }

    @Data
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class TemplateButtonDto {
        private String type; // QUICK_REPLY, PHONE_NUMBER, URL, FLOW
        private String text;
        private String url;
        private String urlSample;

        @com.fasterxml.jackson.annotation.JsonAlias({"phone_number", "phoneNumber", "phone"})
        private String phoneNumber;

        @com.fasterxml.jackson.annotation.JsonAlias({"flow_id", "flowId"})
        private String flowId;

        @com.fasterxml.jackson.annotation.JsonAlias({"flow_action", "flowAction"})
        private String flowAction; // default: "navigate"

        @com.fasterxml.jackson.annotation.JsonAlias({"navigate_screen", "navigateScreen"})
        private String navigateScreen;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUrlSample() { return urlSample; }
        public void setUrlSample(String urlSample) { this.urlSample = urlSample; }
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
        public String getFlowId() { return flowId; }
        public void setFlowId(String flowId) { this.flowId = flowId; }
        public String getFlowAction() { return flowAction; }
        public void setFlowAction(String flowAction) { this.flowAction = flowAction; }
        public String getNavigateScreen() { return navigateScreen; }
        public void setNavigateScreen(String navigateScreen) { this.navigateScreen = navigateScreen; }
    }
}
