package com.chatcrmlite.backend.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateCampaignRequestDto {
    private String name;
    private String templateId;
    private String variableMappingJson;
    private LocalDateTime scheduledAt;
}
