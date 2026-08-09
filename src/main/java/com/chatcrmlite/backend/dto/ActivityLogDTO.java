package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.ActivityLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for ActivityLog to avoid lazy initialization exceptions.
 * 
 * Includes only the necessary fields for the frontend timeline UI,
 * without triggering lazy-loaded relationships.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityLogDTO {
    
    private UUID id;
    
    // Owner info (flattened)
    private UUID ownerId;
    private String ownerEmail;
    private String ownerName;
    
    // Contact info (flattened)
    private UUID contactId;
    private String contactName;
    private String contactEmail;
    private String contactPhone;
    
    // Domain reference
    private String entityType;
    private UUID entityId;
    
    // Activity detail
    private String activityType;
    private String source;
    private String summary;
    private String payload;
    
    // Timestamp
    private LocalDateTime createdAt;
    
    /**
     * Convert ActivityLog entity to DTO.
     * Safely accesses lazy-loaded relationships within an active session.
     */
    public static ActivityLogDTO fromEntity(ActivityLog log) {
        // Safely resolve contact name — fall back to waId if name not set
        String contactName = null;
        String contactEmail = null;
        String contactPhone = null;
        UUID contactId = null;
        if (log.getContact() != null) {
            contactId = log.getContact().getId();
            contactName = log.getContact().getName() != null && !log.getContact().getName().isBlank()
                    ? log.getContact().getName()
                    : log.getContact().getWaId();
            contactEmail = log.getContact().getEmail();
            contactPhone = log.getContact().getWaId();
        }

        // Safely resolve owner name — fall back to email if displayName not set
        String ownerName = null;
        String ownerEmail = null;
        UUID ownerId = null;
        if (log.getOwner() != null) {
            ownerId = log.getOwner().getId();
            ownerEmail = log.getOwner().getEmail();
            ownerName = log.getOwner().getDisplayName() != null && !log.getOwner().getDisplayName().isBlank()
                    ? log.getOwner().getDisplayName()
                    : log.getOwner().getEmail();
        }

        return ActivityLogDTO.builder()
                .id(log.getId())
                .ownerId(ownerId)
                .ownerEmail(ownerEmail)
                .ownerName(ownerName)
                .contactId(contactId)
                .contactName(contactName)
                .contactEmail(contactEmail)
                .contactPhone(contactPhone)
                .entityType(log.getEntityType())
                .entityId(log.getEntityId())
                .activityType(log.getActivityType())
                .source(log.getSource())
                .summary(log.getSummary())
                .payload(log.getPayload())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
