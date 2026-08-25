package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid Search using Reciprocal Rank Fusion (RRF) of:
 *   1. Vector similarity (pgvector cosine distance <=>)
 *   2. BM25-style keyword similarity (pg_trgm word_similarity)
 *
 * RRF score formula: score(d) = Σ 1/(k + rank_i(d))
 *   where k=60 (standard constant that dampens high-rank differences)
 *
 * Why RRF instead of linear combination?
 *   - No need to tune weights
 *   - Stable across different score distributions
 *   - Naturally handles the case where one signal returns no results
 *
 * Prerequisites:
 *   - CREATE EXTENSION pg_trgm;
 *   - CREATE INDEX idx_chunks_content_trgm ON document_chunks USING gin (content gin_trgm_ops);
 *   - CREATE INDEX idx_chunks_embedding_hnsw ON document_chunks USING hnsw (embedding vector_cosine_ops);
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final JdbcTemplate jdbcTemplate;

    private static final int RRF_K = 60;
    private static final double VECTOR_WEIGHT = 0.7;
    private static final double BM25_WEIGHT = 0.3;

    /**
     * Performs hybrid search and returns content strings ranked by fused score.
     *
     * @param tenantId      Tenant scope for data isolation
     * @param queryEmbedding Pre-computed query embedding vector
     * @param queryText     Original query text for trigram matching
     * @param topK          Number of results to return after fusion
     * @return Ranked list of content strings
     */
    public List<String> hybridSearch(UUID tenantId, float[] queryEmbedding, String queryText, int topK) {
        String embeddingLiteral = Arrays.toString(queryEmbedding);
        int candidateCount = topK * 4; // Fetch more candidates before fusion

        // ── Vector results ──────────────────────────────────────────────────
        List<Map.Entry<UUID, Double>> vectorResults = fetchVectorResults(
                tenantId, embeddingLiteral, candidateCount);

        // ── BM25/Trigram results ─────────────────────────────────────────────
        List<Map.Entry<UUID, Double>> bm25Results = fetchTrigamResults(
                tenantId, queryText, candidateCount);

        // ── RRF Fusion ───────────────────────────────────────────────────────
        Map<UUID, Double> fusedScores = new LinkedHashMap<>();
        applyRRF(vectorResults, fusedScores, VECTOR_WEIGHT);
        applyRRF(bm25Results, fusedScores, BM25_WEIGHT);

        // ── Sort by fused score, take top-K ──────────────────────────────────
        List<UUID> topIds = fusedScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        log.debug("[HybridSearch] Tenant={} | Vector={} | BM25={} | Fused={} | Returning={}",
                tenantId, vectorResults.size(), bm25Results.size(), fusedScores.size(), topIds.size());

        // ── Preserve ranked order ────────────────────────────────────────────
        return fetchContentByIds(topIds, tenantId);
    }

    private List<Map.Entry<UUID, Double>> fetchVectorResults(UUID tenantId, String embeddingLiteral, int limit) {
        String sql = """
                SELECT id, (embedding <=> ?::vector) AS distance
                FROM document_chunks
                WHERE tenant_id = ?
                ORDER BY distance ASC
                LIMIT ?
                """;
        try {
            return jdbcTemplate.query(sql,
                    (rs, rowNum) -> Map.entry(
                            UUID.fromString(rs.getString("id")),
                            rs.getDouble("distance")),
                    embeddingLiteral, tenantId, limit);
        } catch (Exception e) {
            log.warn("[HybridSearch] Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map.Entry<UUID, Double>> fetchTrigamResults(UUID tenantId, String query, int limit) {
        // word_similarity uses pg_trgm. We use > 0.05 threshold to support multi-line & multi-sentence queries
        String sql = """
                SELECT id, word_similarity(?, content) AS sim
                FROM document_chunks
                WHERE tenant_id = ?
                  AND word_similarity(?, content) > 0.05
                ORDER BY sim DESC
                LIMIT ?
                """;
        try {
            return jdbcTemplate.query(sql,
                    (rs, rowNum) -> Map.entry(
                            UUID.fromString(rs.getString("id")),
                            rs.getDouble("sim")),
                    query, tenantId, query, limit);
        } catch (Exception e) {
            log.warn("[HybridSearch] Trigram search failed (pg_trgm not installed?): {}", e.getMessage());
            return List.of();
        }
    }


    private void applyRRF(List<Map.Entry<UUID, Double>> rankedList,
                          Map<UUID, Double> scores, double weight) {
        for (int rank = 0; rank < rankedList.size(); rank++) {
            UUID id = rankedList.get(rank).getKey();
            double rrfScore = weight * (1.0 / (RRF_K + rank + 1));
            scores.merge(id, rrfScore, Double::sum);
        }
    }

    private List<String> fetchContentByIds(List<UUID> ids, UUID tenantId) {
        if (ids.isEmpty()) return List.of();

        // Fetch all, then sort client-side to preserve RRF order
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(", "));
        String sql = "SELECT id, content FROM document_chunks WHERE id IN (" + placeholders + ") AND tenant_id = ?";

        Object[] params = new Object[ids.size() + 1];
        for (int i = 0; i < ids.size(); i++) params[i] = ids.get(i);
        params[ids.size()] = tenantId;

        Map<UUID, String> contentMap = new HashMap<>();
        jdbcTemplate.query(sql, params, rs -> {
            contentMap.put(UUID.fromString(rs.getString("id")), rs.getString("content"));
        });

        // Return in RRF-ranked order
        return ids.stream()
                .map(contentMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
