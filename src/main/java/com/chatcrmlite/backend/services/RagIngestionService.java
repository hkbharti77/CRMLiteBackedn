package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.DocumentChunk;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class RagIngestionService {
    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);

    @Autowired
    private EmbeddingPersistenceService persistenceService;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private SemanticChunker semanticChunker;

    @Async
    @Transactional
    public CompletableFuture<Map<String, Object>> ingestDocument(MultipartFile file, UUID tenantId) {
        try {
            String text = extractText(file);
            return ingestText(text, tenantId, file.getOriginalFilename());
        } catch (Exception e) {
            log.error("Ingestion failed for tenant {}: {}", tenantId, e.getMessage());
            Map<String, Object> status = new HashMap<>();
            status.put("status", "FAILED");
            status.put("error", e.getMessage());
            return CompletableFuture.completedFuture(status);
        }
    }

    @Async
    @Transactional
    public CompletableFuture<Map<String, Object>> ingestText(String text, UUID tenantId, String source) {
        UUID documentId = UUID.randomUUID();
        Map<String, Object> status = new HashMap<>();
        status.put("documentId", documentId);
        status.put("status", "PROCESSING");

        try {
            if (text == null || text.isBlank()) {
                throw new RuntimeException("Empty content");
            }

            // Use the new SemanticChunker for better context preservation
            List<String> chunks = semanticChunker.chunk(text);
            List<DocumentChunk> docChunks = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                
                String hash = hashContent(chunk);
                // Convert vector to String for the ColumnTransformer
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

            log.info("Text ingestion completed for tenant {}. Chunks: {}", tenantId, savedCount);
            status.put("status", "COMPLETED");
            status.put("chunksCount", savedCount);
            return CompletableFuture.completedFuture(status);

        } catch (Exception e) {
            log.error("Text ingestion failed for tenant {}: {}", tenantId, e.getMessage());
            status.put("status", "FAILED");
            status.put("error", e.getMessage());
            return CompletableFuture.completedFuture(status);
        }
    }

    private String extractText(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename == null) return null;

        try (InputStream is = file.getInputStream()) {
            if (filename.endsWith(".pdf")) {
                try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(is.readAllBytes()))) {
                    return new PDFTextStripper().getText(doc);
                }
            } else if (filename.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(is)) {
                    return doc.getParagraphs().stream()
                            .map(XWPFParagraph::getText)
                            .collect(Collectors.joining("\n"));
                }
            } else {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
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
