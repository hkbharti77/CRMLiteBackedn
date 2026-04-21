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

    List<DocumentChunk> findByDocumentIdAndTenantId(UUID documentId, UUID tenantId);

    void deleteByDocumentIdAndTenantId(UUID documentId, UUID tenantId);

    /**
     * Hybrid Search: Vector Similarity + Trigram Similarity (Keyword)
     * Ordered by: (vector_dist - 0.1 * LEAST(keyword_sim, 1.0)), vector_dist ASC
     * Note: We use native query for pgvector operators and trigram similarity
     */
    @Query(value = "SELECT DISTINCT document_id, metadata->>'source' as source_name " +
                   "FROM document_chunks " +
                   "WHERE tenant_id = :tenantId", nativeQuery = true)
    List<Object[]> findDistinctDocumentsByTenantId(@Param("tenantId") UUID tenantId);

}
