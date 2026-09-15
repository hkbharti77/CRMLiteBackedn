package com.chatcrmlite.backend.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Fused context ready for PromptBuilder / hallucination checks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FusedContext {
    @Builder.Default
    private List<String> vectorContextLines = new ArrayList<>();
    @Builder.Default
    private List<String> graphContextLines = new ArrayList<>();
    @Builder.Default
    private List<String> sources = new ArrayList<>();
    @Builder.Default
    private List<RetrievalResult> rankedEvidence = new ArrayList<>();
    private long fusionLatencyMs;
    private int contextCharCount;

    /** Flat chunk list for backward-compatible PromptBuilder / hallucination detector. */
    public List<String> asFlatChunks() {
        List<String> flat = new ArrayList<>();
        flat.addAll(vectorContextLines);
        if (!graphContextLines.isEmpty()) {
            flat.add("GRAPH_RELATIONS:\n" + String.join("\n", graphContextLines));
        }
        return flat;
    }
}
