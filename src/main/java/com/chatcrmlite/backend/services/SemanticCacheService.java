package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Optimized Semantic Cache using PostgreSQL pgvector.
 * 
 * Replaces the previous Redis-based implementation which required 
 * loading all entries into JVM memory for a linear scan.
 * 
 * Benefits:
 * 1. O(log N) lookup using HNSW index.
 * 2. Zero JVM GC pressure (search happens in DB).
 * 3. Scalable to millions of entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private final JdbcTemplate jdbcTemplate;

    private static final double SIMILARITY_THRESHOLD = 0.92; // 1 - cosine_distance

    /**
     * Finds a cached response for a similar query using vector distance.
     */
    public String getCachedResponse(float[] queryEmbedding, UUID tenantId) {
        String embeddingLiteral = Arrays.toString(queryEmbedding);
        
        // 1 - (embedding <=> query) = similarity
        // threshold 0.92 means distance must be < 0.08
        String sql = """
                SELECT response_text, id
                FROM semantic_cache
                WHERE tenant_id = ?
                  AND (embedding <=> CAST(? AS vector)) < ?
                ORDER BY (embedding <=> CAST(? AS vector)) ASC
                LIMIT 1
                """;

        double distanceThreshold = 1.0 - SIMILARITY_THRESHOLD;

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, 
                    tenantId, embeddingLiteral, distanceThreshold, embeddingLiteral);

            if (!results.isEmpty()) {
                String response = (String) results.get(0).get("response_text");
                UUID entryId = (UUID) results.get(0).get("id");
                
                // Async update last accessed time (fire and forget)
                updateLastAccessed(entryId);
                
                log.info("[SemanticCache] HIT for tenant {}", tenantId);
                return response;
            }
        } catch (Exception e) {
            log.error("[SemanticCache] Lookup failed: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Saves a new entry to the semantic cache.
     */
    public void putCachedResponse(String query, float[] queryEmbedding, String response, UUID tenantId) {
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
