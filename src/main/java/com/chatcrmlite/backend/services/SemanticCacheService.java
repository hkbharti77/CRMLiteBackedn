package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Optimized Semantic & Exact Cache using PostgreSQL pgvector.
 * 
 * Benefits:
 * 1. O(1) exact query string match fast path.
 * 2. O(log N) HNSW vector similarity lookup.
 * 3. Zero JVM GC pressure (search happens in DB).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${ai.cache.similarity-threshold:0.85}")
    private double similarityThreshold = 0.85;

    public String getCachedResponse(float[] queryEmbedding, UUID tenantId) {
        return getCachedResponse(null, queryEmbedding, tenantId);
    }

    /**
     * Finds a cached response using 2-step lookup:
     * 1. Exact query text match (O(1) ultra-fast path, < 1ms)
     * 2. Semantic vector distance lookup (< 5ms)
     */
    public String getCachedResponse(String rawQuery, float[] queryEmbedding, UUID tenantId) {
        // 1. Exact query text match check (Instant Fast Path)
        if (rawQuery != null && !rawQuery.isBlank()) {
            String exactSql = """
                    SELECT response_text, id
                    FROM semantic_cache
                    WHERE tenant_id = ?
                      AND LOWER(TRIM(query_text)) = LOWER(TRIM(?))
                    LIMIT 1
                    """;
            try {
                List<Map<String, Object>> exactResults = jdbcTemplate.queryForList(exactSql, tenantId, rawQuery);
                if (!exactResults.isEmpty()) {
                    String response = (String) exactResults.get(0).get("response_text");
                    UUID entryId = (UUID) exactResults.get(0).get("id");
                    if (response != null && response.trim().length() >= 5 && !"bb".equalsIgnoreCase(response.trim())) {
                        updateLastAccessed(entryId);
                        log.info("[SemanticCache] EXACT QUERY HIT for tenant {} | Query: '{}'", tenantId, rawQuery);
                        return response;
                    } else {
                        // Evict dummy/corrupted cache entry
                        try {
                            jdbcTemplate.update("DELETE FROM semantic_cache WHERE id = ?", entryId);
                            log.warn("[SemanticCache] Evicted invalid cached response ('{}') for query '{}'", response, rawQuery);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                log.warn("[SemanticCache] Exact query lookup failed: {}", e.getMessage());
            }
        }

        // 2. Semantic vector distance lookup
        if (queryEmbedding == null) return null;

        String embeddingLiteral = Arrays.toString(queryEmbedding);
        String sql = """
                SELECT response_text, id
                FROM semantic_cache
                WHERE tenant_id = ?
                  AND (embedding <=> CAST(? AS vector)) < ?
                ORDER BY (embedding <=> CAST(? AS vector)) ASC
                LIMIT 1
                """;

        double distanceThreshold = 1.0 - similarityThreshold;

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, 
                    tenantId, embeddingLiteral, distanceThreshold, embeddingLiteral);

            if (!results.isEmpty()) {
                String response = (String) results.get(0).get("response_text");
                UUID entryId = (UUID) results.get(0).get("id");
                if (response != null && response.trim().length() >= 5 && !"bb".equalsIgnoreCase(response.trim())) {
                    updateLastAccessed(entryId);
                    log.info("[SemanticCache] SEMANTIC VECTOR HIT for tenant {}", tenantId);
                    return response;
                } else {
                    try {
                        jdbcTemplate.update("DELETE FROM semantic_cache WHERE id = ?", entryId);
                        log.warn("[SemanticCache] Evicted invalid semantic cached response ('{}')", response);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.error("[SemanticCache] Semantic vector lookup failed: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Saves a new entry to the semantic cache.
     */
    public void putCachedResponse(String query, float[] queryEmbedding, String response, UUID tenantId) {
        if (response == null || response.trim().length() < 5 || "bb".equalsIgnoreCase(response.trim())) {
            log.debug("[SemanticCache] Skipping caching for short or dummy response: '{}'", response);
            return;
        }

        String embeddingLiteral = Arrays.toString(queryEmbedding);
        
        String sql = """
                INSERT INTO semantic_cache (id, tenant_id, query_text, embedding, response_text)
                VALUES (?, ?, ?, CAST(? AS vector), ?)
                ON CONFLICT DO NOTHING
                """;

        try {
            jdbcTemplate.update(sql, UUID.randomUUID(), tenantId, query, embeddingLiteral, response);
            log.debug("[SemanticCache] Saved entry for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("[SemanticCache] Save failed: {}", e.getMessage());
        }
    }

    private void updateLastAccessed(UUID id) {
        // Run in background or just execute quickly
        try {
            jdbcTemplate.update("UPDATE semantic_cache SET last_accessed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        } catch (Exception ignored) {}
    }
}
