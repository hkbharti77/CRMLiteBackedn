package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByTenantId(UUID tenantId);
    
    org.springframework.data.domain.Page<DocumentChunk> findByTenantId(UUID tenantId, org.springframework.data.domain.Pageable pageable);
    
    long countByTenantId(UUID tenantId);

    List<DocumentChunk> findByDocumentIdAndTenantId(UUID documentId, UUID tenantId);

    void deleteByDocumentIdAndTenantId(UUID documentId, UUID tenantId);

    /**
     * Hybrid Search: Vector Similarity + Trigram Similarity (Keyword)
     * Ordered by: (vector_dist - 0.1 * LEAST(keyword_sim, 1.0)), vector_dist ASC
     * Note: We use native query for pgvector operators and trigram similarity
     */
    /**
     * Get document statistics including chunk count
     */
    @Query(value = "SELECT document_id, metadata->>'source' as source_name, COUNT(*) as chunk_count " +
                   "FROM document_chunks " +
                   "WHERE tenant_id = :tenantId " +
                   "GROUP BY document_id, metadata->>'source'", nativeQuery = true)
    List<Object[]> findDocumentStatsByTenantId(@Param("tenantId") UUID tenantId);

    @Query(value = "SELECT content FROM document_chunks " +
                   "WHERE tenant_id = :tenantId " +
                   "ORDER BY embedding <=> cast(:embedding as vector) LIMIT :limit", nativeQuery = true)
    List<String> findSimilarChunks(@Param("tenantId") UUID tenantId, @Param("embedding") String embedding, @Param("limit") int limit);

}
