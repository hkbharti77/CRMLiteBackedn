package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    @Override
    @Query("SELECT d FROM DocumentChunk d WHERE d.id = :id")
    Optional<DocumentChunk> findById(@Param("id") UUID id);

    @Query("SELECT d FROM DocumentChunk d WHERE d.tenant.id = :tenantId")
    List<DocumentChunk> findByTenantId(@Param("tenantId") UUID tenantId);
    
    @Query("SELECT d FROM DocumentChunk d WHERE d.tenant.id = :tenantId")
    org.springframework.data.domain.Page<DocumentChunk> findByTenantId(@Param("tenantId") UUID tenantId, org.springframework.data.domain.Pageable pageable);
    
    @Query("SELECT COUNT(d) FROM DocumentChunk d WHERE d.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM DocumentChunk d WHERE d.tenant.id = :tenantId AND d.contentHash = :contentHash")
    boolean existsByTenantIdAndContentHash(@Param("tenantId") UUID tenantId, @Param("contentHash") String contentHash);

    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM DocumentChunk d WHERE d.documentId = :documentId AND d.chunkIndex = :chunkIndex AND d.isActive = TRUE")
    boolean existsByDocumentIdAndChunkIndexAndIsActiveTrue(@Param("documentId") UUID documentId, @Param("chunkIndex") Integer chunkIndex);

    @Query("SELECT d FROM DocumentChunk d WHERE d.documentId = :documentId AND d.tenant.id = :tenantId")
    List<DocumentChunk> findByDocumentIdAndTenantId(@Param("documentId") UUID documentId, @Param("tenantId") UUID tenantId);

    @Query("SELECT d FROM DocumentChunk d WHERE d.documentId = :documentId AND d.isActive = TRUE ORDER BY d.chunkIndex ASC")
    List<DocumentChunk> findByDocumentIdAndIsActiveTrueOrderByChunkIndexAsc(@Param("documentId") UUID documentId);

    @Query("SELECT d FROM DocumentChunk d WHERE d.documentId = :documentId AND d.tenant.id = :tenantId AND d.isActive = TRUE ORDER BY d.chunkIndex ASC")
    org.springframework.data.domain.Page<DocumentChunk> findByDocumentIdAndTenantIdAndIsActiveTrueOrderByChunkIndexAsc(
            @Param("documentId") UUID documentId, @Param("tenantId") UUID tenantId, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT COUNT(d) FROM DocumentChunk d WHERE d.documentId = :documentId AND d.isActive = TRUE")
    long countByDocumentIdAndIsActiveTrue(@Param("documentId") UUID documentId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE DocumentChunk d SET d.isActive = FALSE, d.updatedAt = :now WHERE d.documentId = :documentId AND d.isActive = TRUE")
    int deactivateActiveChunks(@Param("documentId") UUID documentId, @Param("now") java.time.Instant now);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE DocumentChunk d SET d.isActive = TRUE, d.updatedAt = :now WHERE d.documentId = :documentId AND d.version = :version")
    int activateCandidateChunks(@Param("documentId") UUID documentId, @Param("version") Integer version, @Param("now") java.time.Instant now);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM DocumentChunk d WHERE d.documentId = :documentId AND d.version = :version AND d.isActive = FALSE")
    int deleteCandidateChunks(@Param("documentId") UUID documentId, @Param("version") Integer version);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM DocumentChunk d WHERE d.documentId = :documentId AND d.tenant.id = :tenantId")
    void deleteByDocumentIdAndTenantId(@Param("documentId") UUID documentId, @Param("tenantId") UUID tenantId);

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
