package com.chatcrmlite.backend.dto.crm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergeResultDTO {
    private boolean success;
    private UUID primaryContactId;
    private UUID secondaryContactId;
    private Map<String, Integer> transferredCounts;
    private Instant mergedAt;
    private String message;
}
