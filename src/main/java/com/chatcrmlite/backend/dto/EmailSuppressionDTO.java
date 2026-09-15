package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.email.EmailSuppressionList.SuppressionReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailSuppressionDTO {
    private UUID id;
    private String email;
    private SuppressionReason reason;
    private UUID sourceCampaignId;
    private LocalDateTime createdAt;
    private UUID createdBy;
}
