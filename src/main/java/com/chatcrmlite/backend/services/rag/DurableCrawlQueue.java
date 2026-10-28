package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.models.RagCrawlJob;
import com.chatcrmlite.backend.repositories.RagCrawlJobRepository;
import com.chatcrmlite.backend.security.SsrfProtectionValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DurableCrawlQueue {

    private final RagCrawlJobRepository crawlJobRepository;
    private final SsrfProtectionValidator ssrfValidator;

    /**
     * Enqueues a new crawl job with deduplication based on tenantId and idempotencyKey.
     */
    @Transactional
    public RagCrawlJob enqueueJob(UUID tenantId, String sourceUrl, String crawlType, String idempotencyKey, String createdBy) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("Source URL cannot be empty");
        }

        // Basic SSRF syntax check before enqueuing
        ssrfValidator.validateUrl(sourceUrl);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<RagCrawlJob> existing = crawlJobRepository.findActiveByIdempotencyKey(
                    tenantId, idempotencyKey, List.of("QUEUED", "CRAWLING", "RETRYING"));
            if (existing.isPresent()) {
                log.info("Returning existing active crawl job {} for idempotency key '{}'", existing.get().getId(), idempotencyKey);
                return existing.get();
            }
        }

        RagCrawlJob job = RagCrawlJob.builder()
                .tenantId(tenantId)
                .sourceUrl(sourceUrl.trim())
                .crawlType(crawlType != null ? crawlType : "SINGLE_PAGE")
                .status("QUEUED")
                .idempotencyKey(idempotencyKey)
                .claimGeneration(0)
                .pagesDiscovered(1)
                .pagesCrawled(0)
                .pagesFailed(0)
                .totalChunks(0)
                .attemptCount(0)
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .build();

        RagCrawlJob saved = crawlJobRepository.save(job);
        log.info("Enqueued RAG crawl job {} for tenant {} (url: {}, type: {})", 
                saved.getId(), tenantId, saved.getSourceUrl(), saved.getCrawlType());
        return saved;
    }

    /**
     * Claims the next available job atomically using SELECT FOR UPDATE SKIP LOCKED.
     * Increments claimGeneration to guard against zombie workers.
     */
    @Transactional
    public Optional<RagCrawlJob> claimNextJob(String workerId, Duration leaseDuration) {
        Instant now = Instant.now();
        Optional<RagCrawlJob> candidate = crawlJobRepository.findNextExecutableJobForUpdate(now);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        RagCrawlJob job = candidate.get();
        job.setStatus("CRAWLING");
        job.setLockedBy(workerId);
        job.setClaimGeneration(job.getClaimGeneration() + 1);
        if (job.getStartedAt() == null) {
            job.setStartedAt(now);
        }
        job.setHeartbeatAt(now);
        job.setLeaseExpiresAt(now.plus(leaseDuration));
        job.setAttemptCount(job.getAttemptCount() + 1);

        RagCrawlJob claimed = crawlJobRepository.save(job);
        log.info("Worker '{}' claimed crawl job {} (gen: {}, attempt: {})", 
                workerId, claimed.getId(), claimed.getClaimGeneration(), claimed.getAttemptCount());
        return Optional.of(claimed);
    }

    /**
     * Renews lease on a running job using fencing token.
     */
    @Transactional
    public boolean renewHeartbeat(UUID jobId, int claimGen, Duration leaseExtension) {
        Instant now = Instant.now();
        Instant newExpiry = now.plus(leaseExtension);
        int rows = crawlJobRepository.updateHeartbeat(jobId, claimGen, now, newExpiry);
        return rows > 0;
    }

    /**
     * Updates live progress counters.
     */
    @Transactional
    public boolean updateProgress(UUID jobId, int claimGen, int discovered, int crawled, int failed, int chunks) {
        int rows = crawlJobRepository.updateProgress(jobId, claimGen, discovered, crawled, failed, chunks, Instant.now());
        return rows > 0;
    }

    /**
     * Completes a crawl job with final outcome and fencing check.
     */
    @Transactional
    public boolean completeJob(UUID jobId, int claimGen, String status, int pagesCrawled, int pagesFailed, int totalChunks,
                               String failuresJson, String errorMsg, UUID documentId, String documentName) {
        int rows = crawlJobRepository.completeJob(jobId, claimGen, status, pagesCrawled, pagesFailed, totalChunks,
                failuresJson, errorMsg, documentId, documentName, Instant.now());
        return rows > 0;
    }

    /**
     * Cancels a queued or running job.
     */
    @Transactional
    public boolean cancelJob(UUID jobId, UUID tenantId) {
        int rows = crawlJobRepository.cancelJob(jobId, tenantId, Instant.now());
        if (rows > 0) {
            log.info("Cancelled crawl job {} for tenant {}", jobId, tenantId);
        }
        return rows > 0;
    }
}
