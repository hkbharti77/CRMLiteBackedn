package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsentSummaryDTO {

    private long totalContacts;

    private ChannelMetrics whatsapp;
    private ChannelMetrics email;
    private ChannelMetrics sms;

    private long globallySuppressedCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelMetrics {
        private long optedIn;
        private long optedOut;
        private long unknown;
    }
}
