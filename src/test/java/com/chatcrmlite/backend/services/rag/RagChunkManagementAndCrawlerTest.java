package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.models.DocumentChunk;
import com.chatcrmlite.backend.models.KnowledgeDocument;
import com.chatcrmlite.backend.models.RagCrawlJob;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import com.chatcrmlite.backend.repositories.KnowledgeDocumentRepository;
import com.chatcrmlite.backend.repositories.RagCrawlJobRepository;
import com.chatcrmlite.backend.security.SsrfProtectionValidator;
import com.chatcrmlite.backend.services.SemanticChunker;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagChunkManagementAndCrawlerTest {

    @Mock
    private KnowledgeDocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository chunkRepository;

    @Mock
    private SemanticChunker semanticChunker;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private RagCrawlJobRepository crawlJobRepository;

    @Mock
    private SsrfProtectionValidator ssrfValidator;

    @InjectMocks
    private StagedReindexService stagedReindexService;

    @InjectMocks
    private DurableCrawlQueue durableCrawlQueue;

    private UUID tenantId;
    private UUID docId;
    private Tenant tenant;
    private KnowledgeDocument document;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        docId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);

        document = KnowledgeDocument.builder()
                .id(docId)
                .tenant(tenant)
                .name("Company Policy Handbook")
                .activeVersion(1)
                .status("ACTIVE")
                .totalChunks(2)
                .build();
    }

    @Test
    @DisplayName("Staged reindex should successfully generate candidate chunks under version 2 and atomically swap")
    void testStagedReindex_Success() {
        DocumentChunk chunk1 = DocumentChunk.builder()
                .documentId(docId)
                .chunkIndex(0)
                .version(1)
                .isActive(true)
                .content("Initial section content")
                .build();
        DocumentChunk chunk2 = DocumentChunk.builder()
                .documentId(docId)
                .chunkIndex(1)
                .version(1)
                .isActive(true)
                .content("Second section content")
                .build();

        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocumentIdAndIsActiveTrueOrderByChunkIndexAsc(docId))
                .thenReturn(List.of(chunk1, chunk2));
        when(semanticChunker.chunk(anyString())).thenReturn(List.of("New chunk 1 text", "New chunk 2 text"));

        float[] mockVector = new float[384];
        Arrays.fill(mockVector, 0.1f);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(mockVector)));

        when(documentRepository.findByIdForUpdate(docId)).thenReturn(Optional.of(document));
        when(documentRepository.updateActiveVersion(eq(docId), eq(2), eq(1))).thenReturn(1);

        Map<String, Object> result = stagedReindexService.reindexDocument(tenantId, docId);

        assertNotNull(result);
        assertEquals("COMPLETED", result.get("status"));
        assertEquals(1, result.get("previousVersion"));
        assertEquals(2, result.get("activeVersion"));
        assertEquals(2, result.get("totalChunks"));

        verify(chunkRepository).saveAll(anyList());
        verify(chunkRepository).deactivateActiveChunks(eq(docId), any(Instant.class));
        verify(chunkRepository).activateCandidateChunks(eq(docId), eq(2), any(Instant.class));
        verify(documentRepository).updateActiveVersion(eq(docId), eq(2), eq(1));
    }

    @Test
    @DisplayName("Staged reindex should rollback candidate chunks if embedding fails")
    void testStagedReindex_RollbackOnEmbeddingFailure() {
        DocumentChunk chunk1 = DocumentChunk.builder()
                .documentId(docId)
                .chunkIndex(0)
                .version(1)
                .isActive(true)
                .content("Valid content")
                .build();

        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocumentIdAndIsActiveTrueOrderByChunkIndexAsc(docId))
                .thenReturn(List.of(chunk1));
        when(semanticChunker.chunk(anyString())).thenReturn(List.of("New chunk"));

        // Simulate embedding generation failure (e.g. model timeout)
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Embedding model timeout"));

        assertThrows(RuntimeException.class, () ->
                stagedReindexService.reindexDocument(tenantId, docId));

        // Candidate chunks for version 2 must be deleted so existing version 1 remains active
        verify(chunkRepository).deleteCandidateChunks(eq(docId), eq(2));
        verify(chunkRepository, never()).deactivateActiveChunks(any(), any());
    }

    @Test
    @DisplayName("DurableCrawlQueue should deduplicate identical queued crawl jobs with idempotency key")
    void testDurableCrawlQueue_Idempotency() {
        RagCrawlJob existing = RagCrawlJob.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .sourceUrl("https://example.com/docs")
                .idempotencyKey("idem-key-123")
                .status("QUEUED")
                .build();

        when(crawlJobRepository.findActiveByIdempotencyKey(eq(tenantId), eq("idem-key-123"), any()))
                .thenReturn(Optional.of(existing));

        RagCrawlJob job = durableCrawlQueue.enqueueJob(tenantId, "https://example.com/docs", "SINGLE_PAGE", "idem-key-123", "admin@test.com");

        assertSame(existing, job);
        verify(crawlJobRepository, never()).save(any());
    }

    @Test
    @DisplayName("DurableCrawlQueue should increment claimGeneration on worker lease claim")
    void testDurableCrawlQueue_WorkerClaimGeneration() {
        UUID jobId = UUID.randomUUID();
        RagCrawlJob queuedJob = RagCrawlJob.builder()
                .id(jobId)
                .tenantId(tenantId)
                .sourceUrl("https://example.com")
                .status("QUEUED")
                .claimGeneration(0)
                .attemptCount(0)
                .build();

        when(crawlJobRepository.findNextExecutableJobForUpdate(any(Instant.class)))
                .thenReturn(Optional.of(queuedJob));
        when(crawlJobRepository.save(any(RagCrawlJob.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<RagCrawlJob> claimed = durableCrawlQueue.claimNextJob("worker-node-1", Duration.ofMinutes(15));

        assertTrue(claimed.isPresent());
        RagCrawlJob job = claimed.get();
        assertEquals("CRAWLING", job.getStatus());
        assertEquals("worker-node-1", job.getLockedBy());
        assertEquals(1, job.getClaimGeneration());
        assertEquals(1, job.getAttemptCount());
        assertNotNull(job.getLeaseExpiresAt());
    }
}
