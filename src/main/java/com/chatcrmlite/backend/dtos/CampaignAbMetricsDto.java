package com.chatcrmlite.backend.dtos;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignAbMetricsDto {
    private UUID campaignId;
    private String campaignName;
    private String abStatus; // TESTING, EVALUATING, WINNER_PROMOTED, DISABLED
    private boolean isAbTest;
    private String winnerMetric; // CLICK_RATE, READ_RATE
    private LocalDateTime evaluationEndsAt;
    private Long remainingMinutes;
    private String winningVariantLabel;
    private int totalAudience;
    private int testSampleCount;
    private int holdoutCount;
    private List<VariantMetricsDto> variants;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VariantMetricsDto {
        private UUID id;
        private String variantLabel;
        private String templateName;
        private int sampleCount;
        private int sentCount;
        private int deliveredCount;
        private int readCount;
        private int clickCount;
        private double readRate;
        private double ctr;
        private boolean isWinner;
        private boolean isLeader;
    }
}
