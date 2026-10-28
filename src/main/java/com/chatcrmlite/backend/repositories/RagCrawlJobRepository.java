package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.RagCrawlJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RagCrawlJobRepository extends JpaRepository<RagCrawlJob, UUID> {

    Optional<RagCrawlJob> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Optional<RagCrawlJob> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<RagCrawlJob> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    @Query("SELECT j FROM RagCrawlJob j WHERE j.tenantId = :tenantId AND j.idempotencyKey = :key AND j.status IN :statuses")
    Optional<RagCrawlJob> findActiveByIdempotencyKey(@Param("tenantId") UUID tenantId, 
                                                    @Param("key") String key, 
                                                    @Param("statuses") Collection<String> statuses);

    @Query(value = "SELECT * FROM rag_crawl_jobs " +
                   "WHERE status = 'QUEUED' " +
                   "   OR (status IN ('CRAWLING', 'RETRYING') AND lease_expires_at < :now) " +
                   "ORDER BY created_at ASC " +
                   "LIMIT 1 " +
                   "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<RagCrawlJob> findNextExecutableJobForUpdate(@Param("now") Instant now);

    @Modifying
    @Query("UPDATE RagCrawlJob j SET j.heartbeatAt = :now, j.leaseExpiresAt = :newLeaseExpiresAt " +
           "WHERE j.id = :jobId AND j.claimGeneration = :claimGen")
    int updateHeartbeat(@Param("jobId") UUID jobId, 
                        @Param("claimGen") Integer claimGen, 
                        @Param("now") Instant now, 
                        @Param("newLeaseExpiresAt") Instant newLeaseExpiresAt);

    @Modifying
    @Query("UPDATE RagCrawlJob j SET j.pagesDiscovered = :discovered, j.pagesCrawled = :crawled, " +
           "j.pagesFailed = :failed, j.totalChunks = :chunks, j.heartbeatAt = :now " +
           "WHERE j.id = :jobId AND j.claimGeneration = :claimGen")
    int updateProgress(@Param("jobId") UUID jobId, 
                       @Param("claimGen") Integer claimGen, 
                       @Param("discovered") Integer discovered, 
                       @Param("crawled") Integer crawled, 
                       @Param("failed") Integer failed, 
                       @Param("chunks") Integer chunks, 
                       @Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE rag_crawl_jobs SET status = :status, completed_at = :now, " +
                   "pages_crawled = :crawled, pages_failed = :failed, total_chunks = :chunks, " +
                   "partial_failures_json = cast(:failuresJson as jsonb), error_message = :errorMsg, " +
                   "document_id = :documentId, document_name = :documentName " +
                   "WHERE id = :jobId AND claim_generation = :claimGen", nativeQuery = true)
    int completeJob(@Param("jobId") UUID jobId, 
                    @Param("claimGen") Integer claimGen, 
                    @Param("status") String status, 
                    @Param("crawled") Integer crawled,
                    @Param("failed") Integer failed,
                    @Param("chunks") Integer chunks,
                    @Param("failuresJson") String failuresJson, 
                    @Param("errorMsg") String errorMsg, 
                    @Param("documentId") UUID documentId, 
                    @Param("documentName") String documentName, 
                    @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RagCrawlJob j SET j.status = 'CANCELLED', j.completedAt = :now " +
           "WHERE j.id = :jobId AND j.tenantId = :tenantId AND j.status IN ('QUEUED', 'CRAWLING', 'RETRYING')")
    int cancelJob(@Param("jobId") UUID jobId, @Param("tenantId") UUID tenantId, @Param("now") Instant now);
}
