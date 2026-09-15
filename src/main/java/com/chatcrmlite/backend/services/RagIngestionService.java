package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.DocumentChunk;
import com.chatcrmlite.backend.services.ingestion.DocumentExtractionErrorCode;
import com.chatcrmlite.backend.services.ingestion.DocumentExtractionException;
import com.chatcrmlite.backend.services.ingestion.DocumentTextExtractor;
import com.chatcrmlite.backend.services.rag.GraphIngestionService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class RagIngestionService {
    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);

    @Autowired
    private EmbeddingPersistenceService persistenceService;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private SemanticChunker semanticChunker;

    @Autowired
    private DocumentTextExtractor documentTextExtractor;

    @Autowired(required = false)
    private GraphIngestionService graphIngestionService;

    @Async
    @Transactional
    public CompletableFuture<Map<String, Object>> ingestDocument(byte[] fileBytes, String filename, UUID tenantId, UUID documentId) {
        Map<String, Object> status = new HashMap<>();
        status.put("documentId", documentId);
        try {
            String text = documentTextExtractor.extract(fileBytes, filename);
            return ingestText(text, tenantId, filename, documentId);
        } catch (DocumentExtractionException e) {
            log.error("Ingestion extraction failed for tenant {}: [{}] {}", tenantId, e.getCode(), e.getMessage());
            status.put("status", "FAILED");
            status.put("error", e.getMessage());
            status.put("errorCode", e.getCode().name());
            return CompletableFuture.completedFuture(status);
        } catch (Exception e) {
            log.error("Ingestion failed for tenant {}: {}", tenantId, e.getMessage());
            status.put("status", "FAILED");
            status.put("error", e.getMessage());
            status.put("errorCode", DocumentExtractionErrorCode.PARSER_FAILURE.name());
            return CompletableFuture.completedFuture(status);
        }
    }

    @Async
    @Transactional
    public CompletableFuture<Map<String, Object>> ingestText(String text, UUID tenantId, String source) {
        return ingestText(text, tenantId, source, UUID.randomUUID());
    }

    @Async
    @Transactional
    public CompletableFuture<Map<String, Object>> ingestText(String text, UUID tenantId, String source, UUID documentId) {
        Map<String, Object> status = new HashMap<>();
        status.put("documentId", documentId);
        status.put("status", "PROCESSING");

        try {
            if (text == null || text.isBlank()) {
                status.put("status", "FAILED");
                status.put("error", "Empty content");
                status.put("errorCode", DocumentExtractionErrorCode.EMPTY_DOCUMENT.name());
                return CompletableFuture.completedFuture(status);
            }

            List<String> chunks = semanticChunker.chunk(text);
            List<DocumentChunk> docChunks = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);

                String hash = hashContent(chunk);
                float[] vector = embeddingModel.embed(chunk).content().vector();
                String embeddingString = Arrays.toString(vector);

                DocumentChunk docChunk = DocumentChunk.builder()
                        .documentId(documentId)
                        .tenantId(tenantId)
                        .content(chunk)
                        .contentHash(hash)
                        .embedding(embeddingString)
                        .metadata(Map.of("chunk_index", i, "source", source != null ? source : "raw_text"))
                        .build();

                docChunks.add(docChunk);
            }

            int savedCount = persistenceService.saveChunks(tenantId, docChunks);

            try {
                if (graphIngestionService != null && graphIngestionService.isAvailable()) {
                    graphIngestionService.ingestDocument(tenantId, documentId, source, text, docChunks);
                }
            } catch (Exception ge) {
                log.warn("Graph ingestion skipped after document save: {}", ge.getMessage());
            }

            log.info("Text ingestion completed for tenant {}. Chunks: {}", tenantId, savedCount);
            status.put("status", "COMPLETED");
            status.put("chunksCount", savedCount);
            return CompletableFuture.completedFuture(status);

        } catch (Exception e) {
            log.error("Text ingestion failed for tenant {}: {}", tenantId, e.getMessage());
            status.put("status", "FAILED");
            status.put("error", e.getMessage());
            status.put("errorCode", DocumentExtractionErrorCode.PARSER_FAILURE.name());
            return CompletableFuture.completedFuture(status);
        }
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
