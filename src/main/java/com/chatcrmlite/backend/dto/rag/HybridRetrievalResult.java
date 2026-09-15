package com.chatcrmlite.backend.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HybridRetrievalResult {
    private QueryAnalysis queryAnalysis;
    @Builder.Default
    private List<RetrievalResult> vectorResults = new ArrayList<>();
    @Builder.Default
    private List<GraphEvidence> graphResults = new ArrayList<>();
    private long analysisLatencyMs;
    private long vectorLatencyMs;
    private long graphLatencyMs;
    private boolean vectorDegraded;
    private boolean graphDegraded;
    private Integer graphTraversalDepth;
    private Integer graphNodesReturned;
    private Integer graphRelationshipsReturned;
}
