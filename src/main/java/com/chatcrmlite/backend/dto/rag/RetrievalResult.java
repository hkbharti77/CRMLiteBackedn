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
public class RetrievalResult {
    private String id;
    private UUID tenantId;
    private double score;
    private Double originalScore;
    private String content;
    private RetrievalSource sourceType;
    private String sourceId;
    private String sourceLabel;
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
