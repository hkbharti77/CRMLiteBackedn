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
}
