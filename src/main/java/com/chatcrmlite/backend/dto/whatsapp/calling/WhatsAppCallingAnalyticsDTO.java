package com.chatcrmlite.backend.dto.whatsapp.calling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppCallingAnalyticsDTO {
    private long totalCalls;
    private long inboundCalls;
    private long outboundCalls;
    private long answeredCalls;
    private long rejectedCalls;
    private long failedCalls;
    private long completedCalls;
    private double averageDurationSeconds;
    private long totalDurationSeconds;
    private double answerRate;
    private long failureCount;
    private Map<String, Long> terminationReasons;
    private Map<String, Long> statusBreakdown;
    private Instant from;
    private Instant to;
}
