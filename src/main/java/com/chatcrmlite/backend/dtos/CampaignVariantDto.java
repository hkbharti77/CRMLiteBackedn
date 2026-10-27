package com.chatcrmlite.backend.dtos;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignVariantDto {
    private String variantLabel; // "A", "B", "C", "D"
    private String templateId; // Template UUID or template name
    private String variableMappingJson;
    @Builder.Default
    private Integer samplePercentage = 50;
}
