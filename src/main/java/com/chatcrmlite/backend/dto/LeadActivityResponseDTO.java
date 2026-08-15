package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.LeadActivity;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadActivityResponseDTO {
    private String id;
    private String leadId;
    private String type;
    private String actorName;
    private String metadataJson;
    private LocalDateTime createdAt;

    public static LeadActivityResponseDTO from(LeadActivity activity) {
        if (activity == null) return null;
        String name = activity.getActor() != null ? activity.getActor().getDisplayName() : null;
        if (name == null || name.isBlank()) {
            name = activity.getActor() != null ? activity.getActor().getEmail() : "System";
        }
        return LeadActivityResponseDTO.builder()
                .id(activity.getId() != null ? activity.getId().toString() : null)
                .leadId(activity.getLead() != null && activity.getLead().getId() != null ? activity.getLead().getId().toString() : null)
                .type(activity.getType() != null ? activity.getType().name() : "UNKNOWN")
                .actorName(name)
                .metadataJson(activity.getMetadataJson())
                .createdAt(activity.getCreatedAt())
                .build();
    }
}
