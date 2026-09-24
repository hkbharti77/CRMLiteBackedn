package com.chatcrmlite.backend.dtos.sms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsSendResult {
    private boolean success;
    private String providerMessageId;
    private String providerRequestId; // Unique HTTP Gateway Request ID
    private String provider;
    private String errorCode;
    private String errorMessage;
    @Builder.Default
    private int segments = 1; // Calculated segment count (GSM-7 vs Unicode)
    @Builder.Default
    private BigDecimal unitCost = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal estimatedCost = BigDecimal.ZERO;
    private Map<String, Object> metadata; // Sanitized response metadata (no credentials)
}
