package com.chatcrmlite.backend.dtos;

import com.chatcrmlite.backend.models.WhatsAppCampaign;
import lombok.*;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCampaignRequestDto {
    private String name;
    private String templateId;
    private WhatsAppCampaign.TargetType targetType;
    private String targetFilterJson;
    private String variableMappingJson;
    private Boolean saveImportedRecipients;
    private WhatsAppCampaign.Priority priority;

    // A/B Testing Configuration
    @Builder.Default
    private Boolean isAbTest = false;
    @Builder.Default
    private Integer abTestPercentage = 20;
    @Builder.Default
    private String abWinnerMetric = "CLICK_RATE";
    @Builder.Default
    private Integer abEvaluationHours = 4;
    private java.util.List<CampaignVariantDto> variants;
}
