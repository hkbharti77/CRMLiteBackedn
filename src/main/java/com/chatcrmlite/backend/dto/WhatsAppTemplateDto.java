package com.chatcrmlite.backend.dto;

import lombok.*;
import java.util.List;

@Data
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

    @Data
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
    }
}
