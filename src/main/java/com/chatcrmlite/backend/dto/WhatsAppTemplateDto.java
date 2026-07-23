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

    private List<TemplateButtonDto> buttons;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplateButtonDto {
        private String type; // QUICK_REPLY, PHONE_NUMBER, URL
        private String text;
        private String url;
        private String phoneNumber;
    }
}
