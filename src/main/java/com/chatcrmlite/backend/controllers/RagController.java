
package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.KnowledgeDocument;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import com.chatcrmlite.backend.services.RagIngestionService;
import com.chatcrmlite.backend.services.ingestion.DocumentExtractionErrorCode;
import com.chatcrmlite.backend.services.ingestion.DocumentTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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

    @Autowired
    private DocumentTextExtractor documentTextExtractor;

    @Autowired
    private com.chatcrmlite.backend.services.rag.StagedReindexService stagedReindexService;

    @Autowired
    private com.chatcrmlite.backend.services.rag.TenantQuotaManager quotaManager;

    @Autowired
    private com.chatcrmlite.backend.services.rag.DurableCrawlQueue crawlQueue;

    @Autowired
    private com.chatcrmlite.backend.services.rag.WebCrawlerService webCrawlerService;

    @Autowired
    private com.chatcrmlite.backend.repositories.RagCrawlJobRepository crawlJobRepository;

    @Autowired
    private com.chatcrmlite.backend.repositories.TenantRagQuotaRepository tenantQuotaRepository;

    @Autowired
    private com.chatcrmlite.backend.security.SsrfProtectionValidator ssrfValidator;

    @Autowired
    private dev.langchain4j.model.embedding.EmbeddingModel embeddingModel;

    @Autowired
    private com.chatcrmlite.backend.services.tenant.EntitlementResolverService entitlementResolverService;

    @Autowired
    private com.chatcrmlite.backend.repositories.KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Value("${rag.ingestion.max-upload-bytes:20971520}")
    private long maxUploadBytes;

    private record IngestionTask(UUID tenantId, CompletableFuture<Map<String, Object>> future) {}

    private final Map<UUID, IngestionTask> activeTasks = new java.util.concurrent.ConcurrentHashMap<>();

    private int getMaxDocumentsAllowed(String planId) {
        if (planId == null) return 2;
        switch (planId.toUpperCase()) {
            case "FREE":
                return 2;
            case "STARTER":
                return 5;
            case "GROWTH":
            case "PRO":
                return 25;
            case "SCALE":
                return 100;
            case "ENTERPRISE":
                return 1000;
            default:
                return 2;
        }
    }

    private long getMaxFileBytesAllowed(String planId) {
        if (planId == null) return 2L * 1024 * 1024;
        switch (planId.toUpperCase()) {
            case "FREE":
                return 2L * 1024 * 1024; // 2 MB
            case "STARTER":
                return 5L * 1024 * 1024; // 5 MB
            case "GROWTH":
            case "PRO":
                return 20L * 1024 * 1024; // 20 MB
            case "SCALE":
                return 50L * 1024 * 1024; // 50 MB
            case "ENTERPRISE":
                return 100L * 1024 * 1024; // 100 MB
            default:
                return 2L * 1024 * 1024;
        }
    }

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

        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();

        // 1. Subscription Entitlement Check
        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        String planId = entitlements != null && entitlements.getBasePlanId() != null ? entitlements.getBasePlanId().toUpperCase() : "FREE";
        boolean hasRag = (entitlements != null && entitlements.getFeatures() != null && entitlements.getFeatures().isHasRagLlm()) || "FREE".equalsIgnoreCase(planId);
        if (!hasRag) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "RAG Document Ingestion is not enabled on your current subscription plan.");
            err.put("errorCode", "FEATURE_LOCKED");
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(err);
        }

        int maxDocs = getMaxDocumentsAllowed(planId);
        long planMaxFileBytes = getMaxFileBytesAllowed(planId);

        // 2. Active Document Count Limit Check
        long activeDocs = knowledgeDocumentRepository.countByTenantIdAndStatus(tenantId, "ACTIVE");
        if (activeDocs >= maxDocs) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Active document limit reached (" + activeDocs + "/" + maxDocs + " documents). Please delete an existing document or upgrade your subscription.");
            err.put("errorCode", "DOCUMENT_LIMIT_REACHED");
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).body(err);
        }

        String filename = file.getOriginalFilename();
        if (!documentTextExtractor.isSupported(filename)) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Unsupported format. Allowed: " + documentTextExtractor.getSupportedExtensions());
            err.put("errorCode", DocumentExtractionErrorCode.UNSUPPORTED_FORMAT.name());
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(err);
        }

        // 3. File Size Validation against Plan Allowance
        long effectiveMaxBytes = Math.min(maxUploadBytes, planMaxFileBytes > 0 ? planMaxFileBytes : maxUploadBytes);
        if (file.getSize() > effectiveMaxBytes) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "File size (" + (file.getSize() / (1024 * 1024)) + " MB) exceeds your plan limit of " + (effectiveMaxBytes / (1024 * 1024)) + " MB");
            err.put("errorCode", DocumentExtractionErrorCode.UPLOAD_TOO_LARGE.name());
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(err);
        }

        UUID docId = UUID.randomUUID();
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (java.io.IOException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Failed to read file: " + e.getMessage());
            err.put("errorCode", DocumentExtractionErrorCode.PARSER_FAILURE.name());
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(err);
        }

        if (fileBytes.length > effectiveMaxBytes) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "File exceeds max upload size of " + effectiveMaxBytes + " bytes");
            err.put("errorCode", DocumentExtractionErrorCode.UPLOAD_TOO_LARGE.name());
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(err);
        }

        // 4. Upfront Atomic Chunk Quota Reservation (Unified Monthly Chunk Limit)
        int estimatedChunks = Math.max(1, (int) Math.ceil((double) file.getSize() / 1500));
        try {
            quotaManager.reserveQuota(tenantId, docId, 0, estimatedChunks);
        } catch (com.chatcrmlite.backend.exceptions.QuotaExceededException qe) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", qe.getMessage());
            err.put("errorCode", "QUOTA_EXCEEDED");
            err.put("limit", qe.getLimit());
            err.put("requested", qe.getRequested());
            err.put("available", qe.getAvailable());
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).body(err);
        }

        CompletableFuture<Map<String, Object>> task = ingestionService.ingestDocument(
                fileBytes, filename, tenantId, docId);

        activeTasks.put(docId, new IngestionTask(tenantId, task));

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Ingestion started in background");
        response.put("documentId", docId);
        response.put("estimatedChunks", estimatedChunks);
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
                
        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();
        repository.deleteByDocumentIdAndTenantId(docId, tenantId);
        knowledgeDocumentRepository.findByIdAndTenantId(docId, tenantId).ifPresent(kd -> {
            kd.setStatus("DELETED");
            kd.setUpdatedAt(java.time.Instant.now());
            knowledgeDocumentRepository.save(kd);
        });
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/documents")
    public ResponseEntity<List<Map<String, Object>>> listDocuments(@AuthenticationPrincipal String email) {
        if (email == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }
        
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
                
        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();
        List<Object[]> docs = repository.findDocumentStatsByTenantId(tenantId);
        List<Map<String, Object>> response = docs.stream().map(d -> {
            Map<String, Object> map = new HashMap<>();
            map.put("documentId", d[0]);
            String docName = (d[1] != null && !((String) d[1]).isBlank()) ? (String) d[1] : null;
            if (docName == null && d[0] instanceof UUID did) {
                docName = knowledgeDocumentRepository.findById(did)
                        .map(KnowledgeDocument::getName)
                        .orElse("Knowledge Document");
            }
            map.put("name", docName != null ? docName : "Knowledge Document");
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

        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();
        List<com.chatcrmlite.backend.models.DocumentChunk> chunks = repository.findByDocumentIdAndTenantId(docId, tenantId);
        
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

    /**
     * Inspect individual active chunks for a document.
     */
    @GetMapping("/documents/{docId}/chunks")
    public ResponseEntity<Map<String, Object>> getDocumentChunks(
            @PathVariable UUID docId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal String email) {

        if (email == null) return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        org.springframework.data.domain.Page<com.chatcrmlite.backend.models.DocumentChunk> chunkPage =
                repository.findByDocumentIdAndTenantIdAndIsActiveTrueOrderByChunkIndexAsc(docId, tenantId, pageable);

        List<Map<String, Object>> items = chunkPage.getContent().stream().map(c -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("chunkIndex", c.getChunkIndex() != null ? c.getChunkIndex() : 0);
            map.put("content", c.getContent());
            map.put("version", c.getVersion() != null ? c.getVersion() : 1);
            map.put("isActive", c.getIsActive() != null ? c.getIsActive() : true);
            map.put("metadata", c.getMetadata());
            map.put("updatedAt", c.getUpdatedAt());
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> resp = new HashMap<>();
        resp.put("chunks", items);
        resp.put("totalElements", chunkPage.getTotalElements());
        resp.put("totalPages", chunkPage.getTotalPages());
        resp.put("currentPage", chunkPage.getNumber());
        resp.put("documentId", docId);
        return ResponseEntity.ok(resp);
    }

    /**
     * Edit a specific chunk's text and recalculate its vector embedding.
     */
    @PutMapping("/chunks/{chunkId}")
    public ResponseEntity<Map<String, Object>> updateChunk(
            @PathVariable UUID chunkId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String email) {

        if (email == null) return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();

        String newContent = body.get("content");
        if (newContent == null || newContent.trim().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Chunk content cannot be empty"));
        }

        com.chatcrmlite.backend.models.DocumentChunk chunk = repository.findById(chunkId)
                .orElseThrow(() -> new NoSuchElementException("Chunk not found: " + chunkId));

        if (!chunk.getTenant().getId().equals(tenantId)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }

        // Recompute vector embedding
        float[] vector = embeddingModel.embed(newContent.trim()).content().vector();
        chunk.setContent(newContent.trim());
        chunk.setEmbedding(java.util.Arrays.toString(vector));
        chunk.setUpdatedAt(java.time.Instant.now());
        repository.save(chunk);

        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Chunk updated and re-embedded successfully");
        resp.put("id", chunk.getId());
        resp.put("chunkIndex", chunk.getChunkIndex());
        resp.put("version", chunk.getVersion());
        return ResponseEntity.ok(resp);
    }

    /**
     * Delete a single chunk.
     */
    @DeleteMapping("/chunks/{chunkId}")
    public ResponseEntity<Map<String, Object>> deleteChunk(
            @PathVariable UUID chunkId,
            @AuthenticationPrincipal String email) {

        if (email == null) return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();

        com.chatcrmlite.backend.models.DocumentChunk chunk = repository.findById(chunkId)
                .orElseThrow(() -> new NoSuchElementException("Chunk not found: " + chunkId));

        if (!chunk.getTenant().getId().equals(tenantId)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }

        long activeChunks = repository.countByDocumentIdAndIsActiveTrue(chunk.getDocumentId());
        if (activeChunks <= 1) {
            return ResponseEntity.badRequest().body(Map.of("error", 
                    "Cannot delete the only chunk of an active document. Delete the document instead."));
        }

        repository.delete(chunk);
        return ResponseEntity.ok(Map.of("message", "Chunk deleted successfully", "id", chunkId));
    }

    /**
     * Non-destructive versioned re-indexing of an existing knowledge document.
     */
    @PostMapping("/documents/{docId}/reindex")
    public ResponseEntity<Map<String, Object>> reindexDocument(
            @PathVariable UUID docId,
            @AuthenticationPrincipal String email) {

        if (email == null) return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();

        Map<String, Object> result = stagedReindexService.reindexDocument(tenantId, docId);
        return ResponseEntity.ok(result);
    }

    /**
     * Submit an automated web crawl or sitemap ingestion job.
     */
    @PostMapping("/crawl")
    public ResponseEntity<Map<String, Object>> submitCrawlJob(
            @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal String email) {

        if (email == null) return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();

        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        String planId = entitlements != null && entitlements.getBasePlanId() != null ? entitlements.getBasePlanId().toUpperCase() : "FREE";
        boolean hasRag = (entitlements != null && entitlements.getFeatures() != null && entitlements.getFeatures().isHasRagLlm()) || "FREE".equalsIgnoreCase(planId);
        if (!hasRag) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "RAG Web Crawler is not enabled on your current subscription plan.");
            err.put("errorCode", "FEATURE_LOCKED");
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(err);
        }

        int maxDocs = getMaxDocumentsAllowed(planId);
        long activeDocs = knowledgeDocumentRepository.countByTenantIdAndStatus(tenantId, "ACTIVE");
        if (activeDocs >= maxDocs) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Active document limit reached (" + activeDocs + "/" + maxDocs + " documents). Please delete an existing document or upgrade your subscription.");
            err.put("errorCode", "DOCUMENT_LIMIT_REACHED");
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).body(err);
        }

        String url = payload.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }

        String crawlType = payload.getOrDefault("crawlType", "SINGLE_PAGE");
        String idempotencyKey = payload.get("idempotencyKey");

        // Validate SSRF defenses
        ssrfValidator.validateUrl(url);

        com.chatcrmlite.backend.models.RagCrawlJob job = crawlQueue.enqueueJob(
                tenantId, url, crawlType, idempotencyKey, user.getEmail());

        // Asynchronously execute crawl
        CompletableFuture.runAsync(() -> webCrawlerService.executeCrawl(job));

        Map<String, Object> resp = new HashMap<>();
        resp.put("jobId", job.getId());
        resp.put("status", job.getStatus());
        resp.put("sourceUrl", job.getSourceUrl());
        resp.put("crawlType", job.getCrawlType());
        resp.put("message", "Crawl job accepted and running in background");
        return ResponseEntity.ok(resp);
    }

    /**
     * Poll crawl progress and partial failure details.
     */
    @GetMapping("/crawl/{jobId}")
    public ResponseEntity<Map<String, Object>> getCrawlJob(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal String email) {

        if (email == null) return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();

        com.chatcrmlite.backend.models.RagCrawlJob job = crawlJobRepository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Crawl job not found: " + jobId));

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", job.getId());
        resp.put("sourceUrl", job.getSourceUrl());
        resp.put("crawlType", job.getCrawlType());
        resp.put("status", job.getStatus());
        resp.put("pagesDiscovered", job.getPagesDiscovered());
        resp.put("pagesCrawled", job.getPagesCrawled());
        resp.put("pagesFailed", job.getPagesFailed());
        int totalChunks = (job.getTotalChunks() != null) ? job.getTotalChunks() : 0;
        if (totalChunks == 0 && job.getDocumentId() != null) {
            totalChunks = knowledgeDocumentRepository.findById(job.getDocumentId())
                    .map(kd -> kd.getTotalChunks() != null ? kd.getTotalChunks() : 0)
                    .orElse(0);
        }
        resp.put("totalChunks", totalChunks);
        resp.put("documentId", job.getDocumentId());
        resp.put("documentName", job.getDocumentName());
        resp.put("partialFailuresJson", job.getPartialFailuresJson());
        resp.put("errorMessage", job.getErrorMessage());
        resp.put("createdAt", job.getCreatedAt());
        resp.put("completedAt", job.getCompletedAt());
        return ResponseEntity.ok(resp);
    }

    /**
     * Cancel an active or queued crawl job.
     */
    @PostMapping("/crawl/{jobId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelCrawlJob(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal String email) {

        if (email == null) return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();

        boolean cancelled = crawlQueue.cancelJob(jobId, tenantId);
        quotaManager.releaseReservation(tenantId, jobId);

        Map<String, Object> resp = new HashMap<>();
        resp.put("jobId", jobId);
        resp.put("cancelled", cancelled);
        resp.put("message", cancelled ? "Crawl job cancelled" : "Job not in cancellable state");
        return ResponseEntity.ok(resp);
    }

    /**
     * Retrieve tenant RAG quotas and usage.
     */
    @GetMapping("/quotas")
    public ResponseEntity<Map<String, Object>> getTenantQuotas(@AuthenticationPrincipal String email) {
        if (email == null) return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        UUID tenantId = (user.getTenant() != null && user.getTenant().getId() != null) ? user.getTenant().getId() : user.getId();

        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        String planId = entitlements != null && entitlements.getBasePlanId() != null ? entitlements.getBasePlanId().toUpperCase() : "FREE";
        boolean hasRag = (entitlements != null && entitlements.getFeatures() != null && entitlements.getFeatures().isHasRagLlm()) || "FREE".equalsIgnoreCase(planId);

        com.chatcrmlite.backend.models.TenantRagQuota quota = quotaManager.getOrCreateQuota(tenantId);

        long activeDocs = knowledgeDocumentRepository.countByTenantIdAndStatus(tenantId, "ACTIVE");
        int maxDocs = getMaxDocumentsAllowed(planId);
        int maxFileSizeMb = (int) (getMaxFileBytesAllowed(planId) / (1024 * 1024));

        Map<String, Object> resp = new HashMap<>();
        resp.put("dailyPageLimit", quota.getDailyPageLimit());
        resp.put("dailyPagesConsumed", quota.getDailyPagesConsumed());
        resp.put("monthlyChunkLimit", quota.getMonthlyChunkLimit());
        resp.put("monthlyChunksConsumed", quota.getMonthlyChunksConsumed());
        resp.put("timezone", quota.getTimezone());
        resp.put("planId", planId);
        resp.put("hasRagLlm", hasRag);
        resp.put("activeDocumentsCount", activeDocs);
        resp.put("maxDocumentsLimit", maxDocs);
        resp.put("maxFileSizeMb", maxFileSizeMb);
        return ResponseEntity.ok(resp);
    }
}
