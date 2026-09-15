package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.dto.rag.FusedContext;
import com.chatcrmlite.backend.dto.rag.GraphEvidence;
import com.chatcrmlite.backend.dto.rag.HybridRetrievalResult;
import com.chatcrmlite.backend.dto.rag.RetrievalResult;
import com.chatcrmlite.backend.dto.rag.RetrievalSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deduplicates, ranks, and budgets vector + graph evidence for the prompt.
 * Does not call the LLM.
 */
@Slf4j
@Service
public class ContextFusionService {

    @Value("${rag.hybrid.vector-weight:0.6}")
    private double vectorWeight;

    @Value("${rag.hybrid.graph-weight:0.4}")
    private double graphWeight;

    private static final int MAX_VECTOR_LINES = 8;
    private static final int MAX_GRAPH_LINES = 40;
    private static final int MAX_CONTEXT_CHARS = 10000;

    public FusedContext fuse(HybridRetrievalResult retrieval) {
        long start = System.currentTimeMillis();
        List<RetrievalResult> vector = retrieval.getVectorResults() != null
                ? new ArrayList<>(retrieval.getVectorResults()) : new ArrayList<>();
        List<GraphEvidence> graph = retrieval.getGraphResults() != null
                ? new ArrayList<>(retrieval.getGraphResults()) : new ArrayList<>();

        boolean hasGraph = !graph.isEmpty();
        List<RetrievalResult> ranked = new ArrayList<>();
        Set<String> contentHashes = new HashSet<>();

        for (RetrievalResult r : vector) {
            String hash = hash(r.getContent());
            if (!contentHashes.add(hash)) {
                continue;
            }
            double hybrid = hasGraph
                    ? vectorWeight * normalize(r.getScore()) + graphWeight * 0.0
                    : normalize(r.getScore());
            r.setOriginalScore(r.getScore());
            r.setScore(hybrid);
            ranked.add(r);
        }

        for (GraphEvidence g : graph) {
            String fact = g.getFact() != null ? g.getFact() : "";
            String hash = hash(fact);
            if (!contentHashes.add(hash)) {
                continue;
            }
            double gScore = g.getConfidence() != null ? g.getConfidence() : 1.0;
            // Prefer shallower hops
            gScore = gScore / (1.0 + Math.max(0, g.getHopDepth() - 1) * 0.15);
            double hybrid = hasGraph
                    ? vectorWeight * 0.0 + graphWeight * gScore
                    : gScore;

            ranked.add(RetrievalResult.builder()
                    .id(g.getEntityId() + "->" + g.getTargetId())
                    .tenantId(g.getTenantId())
                    .score(hybrid)
                    .originalScore(gScore)
                    .content(fact)
                    .sourceType(RetrievalSource.GRAPH)
                    .sourceId(g.getSourceId())
                    .sourceLabel(g.getSourceType())
                    .metadata(java.util.Map.of(
                            "relationship", g.getRelationship() != null ? g.getRelationship() : "",
                            "entity", g.getEntity() != null ? g.getEntity() : "",
                            "target", g.getTarget() != null ? g.getTarget() : ""
                    ))
                    .build());
        }

        ranked.sort(Comparator.comparingDouble(RetrievalResult::getScore).reversed());

        List<String> vectorLines = new ArrayList<>();
        List<String> graphLines = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        int chars = 0;

        for (RetrievalResult r : ranked) {
            if (chars >= MAX_CONTEXT_CHARS) break;
            String line;
            if (r.getSourceType() == RetrievalSource.GRAPH) {
                if (graphLines.size() >= MAX_GRAPH_LINES) continue;
                line = r.getContent();
                graphLines.add(line);
            } else {
                if (vectorLines.size() >= MAX_VECTOR_LINES) continue;
                line = r.getContent();
                vectorLines.add(line);
            }
            chars += line.length();
            if (r.getSourceType() != null && r.getSourceId() != null) {
                sources.add(r.getSourceType().name() + ":" + r.getSourceId()
                        + (r.getSourceLabel() != null ? " (" + r.getSourceLabel() + ")" : ""));
            }
        }

        long fusionMs = System.currentTimeMillis() - start;
        log.info("[ContextFusion] vectorLines={} graphLines={} sources={} chars={} fusionMs={} "
                        + "vectorWeight={} graphWeight={}",
                vectorLines.size(), graphLines.size(), sources.size(), chars, fusionMs,
                vectorWeight, graphWeight);

        return FusedContext.builder()
                .vectorContextLines(vectorLines)
                .graphContextLines(graphLines)
                .sources(new ArrayList<>(sources))
                .rankedEvidence(ranked)
                .fusionLatencyMs(fusionMs)
                .contextCharCount(chars)
                .build();
    }

    private static double normalize(double score) {
        if (Double.isNaN(score) || score < 0) return 0;
        // RRF scores are typically small; clamp to [0,1]
        return Math.min(1.0, score);
    }

    private static String hash(String content) {
        if (content == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(content.hashCode());
        }
    }
}
