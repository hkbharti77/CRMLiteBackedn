package com.chatcrmlite.backend.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryAnalysis {
    private String originalQuery;
    private UUID tenantId;
    private String intent;
    @Builder.Default
    private List<String> entities = new ArrayList<>();
    @Builder.Default
    private List<String> entityTypes = new ArrayList<>();
    @Builder.Default
    private List<String> relationshipHints = new ArrayList<>();
    private boolean requiresGraph;
    private boolean requiresVector;
    private int maxGraphDepth;
    private boolean requiresRag;
}
