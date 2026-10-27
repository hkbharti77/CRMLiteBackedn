package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailAbMetricsDto {
    private UUID campaignId;
    private String campaignName;
    private String abStatus; // 'TESTING', 'EVALUATED', 'COMPLETED'
    private boolean isAbTest;
    private String winnerMetric; // 'OPEN_RATE', 'CLICK_RATE'
    private LocalDateTime evaluationEndsAt;
    private Long remainingMinutes;
    private UUID winningVariantId;
    private String winningVariantCode;
    private String winningVariantName;
    private int totalAudience;
    private int testSampleCount;
    private int holdoutCount;
    private List<EmailVariantDto> variants;
}
