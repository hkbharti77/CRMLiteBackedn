package com.chatcrmlite.backend.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEvidence {
    private UUID tenantId;
    private String entity;
    private String entityId;
    private String relationship;
    private String target;
    private String targetId;
    private String fact;
    private String sourceType;
    private String sourceId;
    private Double confidence;
    private int hopDepth;
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
