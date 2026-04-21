package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.DocumentChunk;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
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

@Slf4j
@Service
public class RagIngestionService {

    @Autowired
    private DocumentChunkRepository repository;

    @Autowired
    private LocalVectorStoreService localStore;

    private final EmbeddingModel embeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();

    private static final int CHUNK_SIZE = 400; // tokens approx
    private static final int CHUNK_OVERLAP = 60;

    /**
     * Async ingestion of a document. Supports PDF, DOCX, TXT.
     */
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

    /**
     * Ingest raw text directly. Supported for legacy training endpoints.
     */
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

            // Cleanup & Chunking
            List<String> chunks = chunkText(text);
            int savedCount = 0;

            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                if (chunk.length() < 30) continue; 

                String hash = hashContent(chunk);
                float[] embedding = embeddingModel.embed(chunk).content().vector();

                DocumentChunk docChunk = DocumentChunk.builder()
                        .documentId(documentId)
                        .tenantId(tenantId)
                        .content(chunk)
                        .contentHash(hash)
                        .embedding(embedding)
                        .metadata(Map.of("chunk_index", i, "source", source != null ? source : "raw_text"))
                        .build();

                try {
                    repository.save(docChunk);
                    localStore.addToMemory(docChunk);
                    savedCount++;
                } catch (Exception e) {
                    log.warn("Duplicate chunk for tenant {}: {}", tenantId, hash);
                }
            }

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
                try (PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
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

    private List<String> chunkText(String text) {
        // Primitive chunking logic (Simple but effective for 5 pages)
        // For better results, use a proper Tokenizer
        String[] words = text.split("\\s+");
        List<String> chunks = new ArrayList<>();
        
        for (int i = 0; i < words.length; i += (CHUNK_SIZE - CHUNK_OVERLAP)) {
            int end = Math.min(i + CHUNK_SIZE, words.length);
            String chunk = String.join(" ", Arrays.copyOfRange(words, i, end));
            chunks.add(chunk);
            if (end == words.length) break;
        }
        return chunks;
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
