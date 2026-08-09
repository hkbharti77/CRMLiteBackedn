package com.chatcrmlite.backend.dtos;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateTenantOverridesRequestDto {

    private Boolean hasWhatsapp;
    private Boolean hasWhatsappCampaign;
    private Boolean hasCustomWidget;
    private Boolean hasRagLlm;
    private Boolean hasEmailCampaign;

    private Integer employeeLimit;
    private Integer primaryResourceLimit;
    private Integer secondaryResourceLimit;
    private Integer ticketLimit;
    private Integer emailLimit;
    private Integer maxRecipientsPerWhatsappCampaign;
    private Integer monthlyWhatsappMessageQuota;

    private WhatsAppCampaign.Priority maxAllowedPriority;

    private BigDecimal customMonthlyInr;
    private BigDecimal customYearlyInr;
    private BigDecimal customMonthlyUsd;
    private BigDecimal customYearlyUsd;

    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveUntil;

    private String reason;
}
