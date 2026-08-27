
package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import com.chatcrmlite.backend.services.RagIngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    @Autowired
    private com.chatcrmlite.backend.repositories.UserRepository userRepository;

    @Autowired
    private RagIngestionService ingestionService;

    @Autowired
    private DocumentChunkRepository repository;


    private record IngestionTask(UUID tenantId, CompletableFuture<Map<String, Object>> future) {}

    private final Map<UUID, IngestionTask> activeTasks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Upload a document for RAG ingestion.
     */
    @PostMapping(value = "/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal String email) {

        if (email == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Unauthorized: Please login first");
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).body(err);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        UUID docId = UUID.randomUUID();
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (java.io.IOException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Failed to read file: " + e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(err);
        }

        CompletableFuture<Map<String, Object>> task = ingestionService.ingestDocument(
                fileBytes, file.getOriginalFilename(), user.getId(), docId);

        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();
        activeTasks.put(docId, new IngestionTask(tenantId, task));

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Ingestion started in background");
        response.put("documentId", docId);
        return ResponseEntity.ok(response);
    }

    /**
     * Check status of ingestion task.
     */
    @GetMapping("/status/{docId}")
    public ResponseEntity<Map<String, Object>> getStatus(
            @PathVariable UUID docId,
            @AuthenticationPrincipal String email) {
        if (email == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }

        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();
        IngestionTask task = activeTasks.get(docId);
        if (task == null || !task.tenantId().equals(tenantId)) {
            return ResponseEntity.notFound().build();
        }

        if (task.future().isDone()) {
            try {
                return ResponseEntity.ok(task.future().get());
            } catch (Exception e) {
                Map<String, Object> error = new HashMap<>();
                error.put("status", "FAILED");
                error.put("error", e.getMessage());
                return ResponseEntity.ok(error);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "PROCESSING");
        return ResponseEntity.ok(response);
    }

    /**
     * Delete a document and all its chunks.
     */
    @org.springframework.transaction.annotation.Transactional
    @DeleteMapping("/documents/{docId}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable UUID docId,
            @AuthenticationPrincipal String email) {

        if (email == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }
        
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
                
        repository.deleteByDocumentIdAndTenantId(docId, user.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/documents")
    public ResponseEntity<List<Map<String, Object>>> listDocuments(@AuthenticationPrincipal String email) {
        if (email == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }
        
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
                
        List<Object[]> docs = repository.findDocumentStatsByTenantId(user.getId());
        List<Map<String, Object>> response = docs.stream().map(d -> {
            Map<String, Object> map = new HashMap<>();
            map.put("documentId", d[0]);
            map.put("name", d[1]);
            map.put("totalChunks", d[2]);
            map.put("embeddingSize", 384);
            map.put("vectorModel", "AllMiniLmL6V2Quantized");
            return map;
        }).collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Download extracted document chunks as a text file.
     */
    @GetMapping("/documents/{docId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadDocumentText(
            @PathVariable UUID docId,
            @AuthenticationPrincipal String email) {

        if (email == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<com.chatcrmlite.backend.models.DocumentChunk> chunks = repository.findByDocumentIdAndTenantId(docId, user.getId());
        
        if (chunks.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Sort chunks by index just in case
        chunks.sort(java.util.Comparator.comparing(c -> 
            c.getMetadata().containsKey("chunk_index") ? Integer.parseInt(c.getMetadata().get("chunk_index").toString()) : 0
        ));

        StringBuilder sb = new StringBuilder();
        String sourceName = "knowledge_document";
        if (chunks.get(0).getMetadata().containsKey("source")) {
            sourceName = chunks.get(0).getMetadata().get("source").toString();
        }

        sb.append("--- AI Extracted Text for Document: ").append(sourceName).append(" ---\n");
        sb.append("Total AI Chunks: ").append(chunks.size()).append("\n");
        sb.append("Vector DB Size: 384 dimensions (AllMiniLmL6V2Quantized)\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            sb.append("--- CHUNK ").append(i + 1).append(" ---\n");
            sb.append(chunks.get(i).getContent()).append("\n\n");
        }

        byte[] textBytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.springframework.core.io.ByteArrayResource resource = new org.springframework.core.io.ByteArrayResource(textBytes);

        String filename = sourceName + ".txt";

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .contentLength(textBytes.length)
                .body(resource);
    }
}
