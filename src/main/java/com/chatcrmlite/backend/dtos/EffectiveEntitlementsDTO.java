package com.chatcrmlite.backend.dtos;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EffectiveEntitlementsDTO {

    private String basePlanId;
    private String basePlanName;
    private boolean isCustomized;
    private Integer entitlementVersion;

    private FeaturesDTO features;
    private LimitsDTO limits;
    private PricingDTO pricing;

    private WhatsAppCampaign.Priority maxAllowedPriority;
    private List<WhatsAppCampaign.Priority> allowedPriorities;

    private Map<String, PropertyTrace> trace;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FeaturesDTO {
        private boolean hasWhatsapp;
        private boolean hasWhatsappCampaign;
        private boolean hasCustomWidget;
        private boolean hasRagLlm;
        private boolean hasEmailCampaign;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LimitsDTO {
        private int employeeLimit;
        private int primaryResourceLimit;
        private int secondaryResourceLimit;
        private int ticketLimit;
        private int emailLimit;
        private int maxRecipientsPerWhatsappCampaign;
        private int monthlyWhatsappMessageQuota;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PricingDTO {
        private BigDecimal monthlyInr;
        private BigDecimal yearlyInr;
        private BigDecimal monthlyUsd;
        private BigDecimal yearlyUsd;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PropertyTrace {
        private Object value;
        private String source; // "TENANT_OVERRIDE" or "BASE_PLAN"
    }
}
