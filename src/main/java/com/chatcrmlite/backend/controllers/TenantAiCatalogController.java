package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.ai.action.AiAction;
import com.chatcrmlite.backend.dto.ai.catalog.CatalogCandidate;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.TenantAiCatalogRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.storage.CloudinaryStorageService;
import com.chatcrmlite.backend.services.storage.CloudinaryStorageService.UploadedAsset;
import com.chatcrmlite.backend.services.storage.FileSecurityValidator;
import com.chatcrmlite.backend.services.storage.FileSecurityValidator.ValidationResult;
import com.chatcrmlite.backend.services.whatsapp.catalog.AiCatalogDecisionService;
import com.chatcrmlite.backend.services.whatsapp.catalog.CatalogCandidateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/ai-catalogs")
@RequiredArgsConstructor
public class TenantAiCatalogController {

    private final TenantAiCatalogRepository catalogRepository;
    private final UserRepository userRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final FileSecurityValidator fileSecurityValidator;
    private final CloudinaryStorageService cloudinaryStorageService;
    private final CatalogCandidateService candidateService;
    private final AiCatalogDecisionService decisionService;

    private User getAuthenticatedUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("Authenticated user not found."));
    }

    private Tenant getTenantFromUser(User user) {
        if (user.getTenant() != null) return user.getTenant();
        throw new IllegalStateException("User does not belong to a tenant.");
    }

    /**
     * GET /api/ai-catalogs
     * Lists all uploaded catalogs for the authenticated tenant.
     */
    @GetMapping
    public ResponseEntity<List<TenantAiCatalog>> listCatalogs(@AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = getTenantFromUser(user);
        return ResponseEntity.ok(catalogRepository.findByTenantId(tenant.getId()));
    }

    /**
     * POST /api/ai-catalogs
     * Multipart upload with magic-bytes validation and Cloudinary compensation delete.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadCatalog(
            @AuthenticationPrincipal String email,
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("aiTriggerInstruction") String aiTriggerInstruction
    ) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = getTenantFromUser(user);

        // 1. File Security & Magic Byte Inspection
        ValidationResult valResult = fileSecurityValidator.validate(file);
        if (!valResult.valid()) {
            return ResponseEntity.badRequest().body(Map.of("error", valResult.errorMessage()));
        }

        // 2. Upload to Cloudinary with tenant-scoped folder
        UploadedAsset asset;
        try {
            asset = cloudinaryStorageService.uploadCatalogAsset(
                    tenant.getId(),
                    file,
                    valResult.detectedMimeType()
            );
        } catch (Exception e) {
            log.error("Cloudinary upload failed for tenant {}: {}", tenant.getId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload file to storage: " + e.getMessage()));
        }

        // 3. Persist to Database with Compensation on Failure
        try {
            TenantAiCatalog catalog = TenantAiCatalog.builder()
                    .tenant(tenant)
                    .title(title)
                    .description(description)
                    .aiTriggerInstruction(aiTriggerInstruction)
                    .mediaType(valResult.mediaType())
                    .mimeType(valResult.detectedMimeType())
                    .fileName(file.getOriginalFilename())
                    .fileSizeBytes(file.getSize())
                    .cloudinaryPublicId(asset.publicId())
                    .cloudinaryResourceType(asset.resourceType())
                    .cloudinaryAssetId(asset.assetId())
                    .cloudinaryUrl(asset.secureUrl())
                    .status(CatalogStatus.ACTIVE)
                    .createdBy(user.getId())
                    .updatedBy(user.getId())
                    .build();

            TenantAiCatalog saved = catalogRepository.save(catalog);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (Exception dbEx) {
            log.error("Database insert failed, executing compensation delete on Cloudinary: {}", dbEx.getMessage());
            // Compensation: delete uploaded asset from Cloudinary so no orphaned storage remains
            cloudinaryStorageService.deleteAsset(asset.publicId(), asset.resourceType());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Database transaction failed. Upload compensated and cleaned up."));
        }
    }

    /**
     * GET /api/ai-catalogs/settings
     * Fetches current configuration for AI catalog dispatch.
     */
    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(@AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = getTenantFromUser(user);

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenant.getId()).orElse(null);
        boolean enabled = config != null && Boolean.TRUE.equals(config.getEnableAiCatalogs());
        int cooldown = (config != null && config.getCatalogSendCooldownSeconds() != null) ? config.getCatalogSendCooldownSeconds() : 300;
        double threshold = (config != null && config.getCatalogRelevanceThreshold() != null) ? config.getCatalogRelevanceThreshold() : 0.65;

        return ResponseEntity.ok(Map.of(
                "enabled", enabled,
                "cooldownSeconds", cooldown,
                "relevanceThreshold", threshold
        ));
    }

    /**
     * PATCH /api/ai-catalogs/settings
     * Toggles tenant-level master switch for WhatsApp AI catalogs.
     */
    @PatchMapping("/settings")
    public ResponseEntity<?> updateSettings(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body
    ) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = getTenantFromUser(user);

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenant.getId())
                .orElseThrow(() -> new NoSuchElementException("WhatsApp configuration not found for tenant."));

        if (body.containsKey("enabled")) {
            config.setEnableAiCatalogs(Boolean.valueOf(body.get("enabled").toString()));
        }
        if (body.containsKey("cooldownSeconds")) {
            config.setCatalogSendCooldownSeconds(Integer.valueOf(body.get("cooldownSeconds").toString()));
        }
        if (body.containsKey("relevanceThreshold")) {
            config.setCatalogRelevanceThreshold(Double.valueOf(body.get("relevanceThreshold").toString()));
        }

        whatsappConfigRepository.save(config);
        return ResponseEntity.ok(Map.of(
                "enabled", config.getEnableAiCatalogs(),
                "cooldownSeconds", config.getCatalogSendCooldownSeconds(),
                "relevanceThreshold", config.getCatalogRelevanceThreshold()
        ));
    }

    /**
     * POST /api/ai-catalogs/test-trigger
     * Interactive Test Sandbox: simulates user query against tenant catalogs without sending WhatsApp messages.
     */
    @PostMapping("/test-trigger")
    public ResponseEntity<?> testTrigger(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body
    ) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = getTenantFromUser(user);
        String query = body.getOrDefault("query", "");

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenant.getId()).orElse(null);
        double threshold = (config != null && config.getCatalogRelevanceThreshold() != null)
                ? config.getCatalogRelevanceThreshold()
                : 0.65;

        List<CatalogCandidate> candidates = candidateService.findCandidates(tenant.getId(), query, threshold);
        AiAction action = decisionService.decideFromCandidates(candidates, query, AiAction.DecisionSource.TEST_SIMULATION);

        String catalogTitle = null;
        if (action.catalogId() != null) {
            catalogTitle = catalogRepository.findByIdAndTenantId(action.catalogId(), tenant.getId())
                    .map(TenantAiCatalog::getTitle)
                    .orElse(null);
        }

        return ResponseEntity.ok(Map.of(
                "decision", action.type().name(),
                "catalogId", action.catalogId() != null ? action.catalogId() : "",
                "catalogTitle", catalogTitle != null ? catalogTitle : "",
                "reason", action.reason() != null ? action.reason() : "",
                "caption", action.caption() != null ? action.caption() : "",
                "candidates", candidates,
                "wouldSend", action.type() == AiAction.AiActionType.SEND_CATALOG
        ));
    }

    /**
     * GET /api/ai-catalogs/{id}
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}")
    public ResponseEntity<TenantAiCatalog> getCatalog(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id
    ) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = getTenantFromUser(user);

        return catalogRepository.findByIdAndTenantId(id, tenant.getId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/ai-catalogs/{id}/file?download=false
     * Authenticated endpoint to stream the catalog document (PDF or image).
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/file")
    public ResponseEntity<?> getCatalogFile(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id,
            @RequestParam(value = "download", defaultValue = "false") boolean download
    ) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = getTenantFromUser(user);

        Optional<TenantAiCatalog> catalogOpt = catalogRepository.findByIdAndTenantId(id, tenant.getId());
        if (catalogOpt.isEmpty() || catalogOpt.get().getStatus() == CatalogStatus.DELETED) {
            return ResponseEntity.notFound().build();
        }

        TenantAiCatalog catalog = catalogOpt.get();
        try {
            byte[] fileBytes = cloudinaryStorageService.fetchCatalogBytes(catalog);
            String safeFilename = (catalog.getFileName() != null && !catalog.getFileName().isBlank())
                    ? catalog.getFileName().replaceAll("[\"\\r\\n]", "_")
                    : "catalog.pdf";

            String mimeType = catalog.getMimeType();
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = safeFilename.toLowerCase().endsWith(".pdf") ? "application/pdf" : "application/octet-stream";
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(mimeType));
            headers.setContentDisposition(
                    org.springframework.http.ContentDisposition.builder(download ? "attachment" : "inline")
                            .filename(safeFilename)
                            .build()
            );
            headers.setContentLength(fileBytes.length);
            headers.setCacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(24)).cachePublic());

            return new ResponseEntity<>(fileBytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Failed to stream catalog file {}: {}", id, e.getMessage());
            if (catalog.getCloudinaryUrl() != null && !catalog.getCloudinaryUrl().isBlank()) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, catalog.getCloudinaryUrl())
                        .build();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve catalog file: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/ai-catalogs/{id}
     * Update title, description, or trigger instructions.
     */
    @PutMapping("/{id:[0-9a-fA-F\\-]{36}}")
    public ResponseEntity<?> updateCatalog(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body
    ) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = getTenantFromUser(user);

        Optional<TenantAiCatalog> catalogOpt = catalogRepository.findByIdAndTenantId(id, tenant.getId());
        if (catalogOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        TenantAiCatalog catalog = catalogOpt.get();
        if (body.containsKey("title") && !body.get("title").isBlank()) {
            catalog.setTitle(body.get("title"));
        }
        if (body.containsKey("description")) {
            catalog.setDescription(body.get("description"));
        }
        if (body.containsKey("aiTriggerInstruction") && !body.get("aiTriggerInstruction").isBlank()) {
            catalog.setAiTriggerInstruction(body.get("aiTriggerInstruction"));
        }
        catalog.setUpdatedBy(user.getId());

        return ResponseEntity.ok(catalogRepository.save(catalog));
    }

    /**
     * DELETE /api/ai-catalogs/{id}
     * Safe asynchronous/compensation deletion: ACTIVE -> DELETING -> DELETED
     */
    @DeleteMapping("/{id:[0-9a-fA-F\\-]{36}}")
    public ResponseEntity<?> deleteCatalog(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id
    ) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = getTenantFromUser(user);

        Optional<TenantAiCatalog> catalogOpt = catalogRepository.findByIdAndTenantId(id, tenant.getId());
        if (catalogOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        TenantAiCatalog catalog = catalogOpt.get();
        catalog.setStatus(CatalogStatus.DELETING);
        catalogRepository.save(catalog);

        // Delete from Cloudinary
        boolean deletedFromCloud = cloudinaryStorageService.deleteAsset(catalog.getCloudinaryPublicId(), catalog.getCloudinaryResourceType());
        if (deletedFromCloud) {
            catalog.setStatus(CatalogStatus.DELETED);
            catalogRepository.save(catalog);
            return ResponseEntity.ok(Map.of("message", "Catalog deleted successfully."));
        } else {
            catalog.setStatus(CatalogStatus.FAILED);
            catalogRepository.save(catalog);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete storage asset from Cloudinary. Marked as FAILED."));
        }
    }

    /**
     * PATCH /api/ai-catalogs/{id}/status
     * Toggle ACTIVE vs DISABLED.
     */
    @PatchMapping("/{id:[0-9a-fA-F\\-]{36}}/status")
    public ResponseEntity<?> updateStatus(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body
    ) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = getTenantFromUser(user);

        Optional<TenantAiCatalog> catalogOpt = catalogRepository.findByIdAndTenantId(id, tenant.getId());
        if (catalogOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        TenantAiCatalog catalog = catalogOpt.get();
        String statusStr = body.get("status");
        try {
            CatalogStatus newStatus = CatalogStatus.valueOf(statusStr.toUpperCase());
            catalog.setStatus(newStatus);
            catalog.setUpdatedBy(user.getId());
            return ResponseEntity.ok(catalogRepository.save(catalog));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid status value."));
        }
    }
}
