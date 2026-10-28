package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.models.DocumentChunk;
import com.chatcrmlite.backend.models.KnowledgeDocument;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import com.chatcrmlite.backend.repositories.KnowledgeDocumentRepository;
import com.chatcrmlite.backend.services.EmbeddingPersistenceService;
import com.chatcrmlite.backend.services.SemanticChunker;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class StagedReindexService {

    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final SemanticChunker semanticChunker;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingPersistenceService persistenceService;
    private final TransactionTemplate transactionTemplate;

    public StagedReindexService(
            KnowledgeDocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository,
            SemanticChunker semanticChunker,
            EmbeddingModel embeddingModel,
            EmbeddingPersistenceService persistenceService,
            PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.semanticChunker = semanticChunker;
        this.embeddingModel = embeddingModel;
        this.persistenceService = persistenceService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Performs a non-destructive versioned re-indexing of an existing knowledge document.
     * Candidate chunks are created under (activeVersion + 1) and validated before an atomic swap.
     */
    public Map<String, Object> reindexDocument(UUID tenantId, UUID documentId) {
        log.info("Starting staged re-index for document {} (tenant: {})", documentId, tenantId);

        KnowledgeDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));

        if (doc.getTenant() == null || !doc.getTenant().getId().equals(tenantId)) {
            throw new SecurityException("Unauthorized access to document " + documentId);
        }

        List<DocumentChunk> existingChunks = chunkRepository.findByDocumentIdAndIsActiveTrueOrderByChunkIndexAsc(documentId);
        if (existingChunks.isEmpty()) {
            throw new IllegalStateException("Cannot reindex document " + documentId + ": No active chunks exist");
        }

        int currentVersion = doc.getActiveVersion() != null ? doc.getActiveVersion() : 1;
        int candidateVersion = currentVersion + 1;

        // Step 1: Reconstruct full content
        StringBuilder fullTextBuilder = new StringBuilder();
        for (DocumentChunk chunk : existingChunks) {
            fullTextBuilder.append(chunk.getContent()).append("\n\n");
        }
        String fullText = fullTextBuilder.toString().trim();

        // Step 2: Generate candidate chunks
        List<String> rawChunks = semanticChunker.chunk(fullText);
        if (rawChunks.isEmpty()) {
            throw new IllegalStateException("Chunking resulted in 0 chunks for document " + documentId);
        }

        List<DocumentChunk> candidateChunks = new ArrayList<>();
        try {
            for (int i = 0; i < rawChunks.size(); i++) {
                String text = rawChunks.get(i);
                float[] vector = embeddingModel.embed(text).content().vector();
                if (vector == null || vector.length != 384) {
                    throw new IllegalStateException("Invalid vector embedding generated: expected 384 dimensions, got " 
                            + (vector != null ? vector.length : "null"));
                }

                String hash = hashContent(text);
                DocumentChunk candidate = DocumentChunk.builder()
                        .documentId(documentId)
                        .tenantId(tenantId)
                        .chunkIndex(i)
                        .version(candidateVersion)
                        .isActive(false) // Inactive staging status
                        .content(text)
                        .contentHash(hash)
                        .embedding(Arrays.toString(vector))
                        .metadata(Map.of("chunk_index", i, "source", (doc.getName() != null ? doc.getName() : "reindexed_document"), "reindexed_at", Instant.now().toString()))
                        .build();

                candidateChunks.add(candidate);
            }

            // Save candidate chunks as inactive
            chunkRepository.saveAll(candidateChunks);
            log.info("Saved {} candidate chunks for document {} under version {}", 
                    candidateChunks.size(), documentId, candidateVersion);

            // Step 3: Perform atomic version swap inside a single database transaction
            transactionTemplate.executeWithoutResult(status -> {
                executeAtomicVersionSwap(documentId, currentVersion, candidateVersion);
            });

            log.info("Successfully completed staged reindex for document {}: version {} -> {}, chunk count: {} -> {}",
                    documentId, currentVersion, candidateVersion, existingChunks.size(), candidateChunks.size());

            Map<String, Object> result = new HashMap<>();
            result.put("documentId", documentId);
            result.put("status", "COMPLETED");
            result.put("previousVersion", currentVersion);
            result.put("activeVersion", candidateVersion);
            result.put("totalChunks", candidateChunks.size());
            return result;

        } catch (Exception e) {
            log.error("Staged reindex failed for document {}. Cleaning up candidate chunks under version {}: {}",
                    documentId, candidateVersion, e.getMessage());
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    chunkRepository.deleteCandidateChunks(documentId, candidateVersion);
                });
            } catch (Exception cleanupErr) {
                log.warn("Failed cleaning up candidate chunks for document {}: {}", documentId, cleanupErr.getMessage());
            }
            throw new RuntimeException("Reindexing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Atomically executes the version swap.
     * Evaluates PostgreSQL dual deferred constraint triggers at commit.
     */
    public void executeAtomicVersionSwap(UUID documentId, int currentVersion, int candidateVersion) {
        // Lock parent document row
        KnowledgeDocument doc = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document row not found during swap: " + documentId));

        Instant now = Instant.now();
        // 1. Deactivate old active chunks
        chunkRepository.deactivateActiveChunks(documentId, now);

        // 2. Activate candidate chunks
        chunkRepository.activateCandidateChunks(documentId, candidateVersion, now);

        // 3. Point document active_version to candidate version
        int updated = documentRepository.updateActiveVersion(documentId, candidateVersion, currentVersion);
        if (updated == 0) {
            throw new IllegalStateException("Failed to update active_version for document " + documentId + 
                    " (concurrent modification detected)");
        }

        int newChunkCount = (int) chunkRepository.countByDocumentIdAndIsActiveTrue(documentId);
        doc.setTotalChunks(newChunkCount);
        doc.setActiveVersion(candidateVersion);
        doc.setUpdatedAt(now);
        documentRepository.save(doc);
    }

    private String hashContent(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] encodedhash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
        for (byte b : encodedhash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
