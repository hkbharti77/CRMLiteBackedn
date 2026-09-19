package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.CatalogStatus;
import com.chatcrmlite.backend.models.TenantAiCatalog;
import com.chatcrmlite.backend.repositories.TenantAiCatalogRepository;
import com.chatcrmlite.backend.services.storage.CloudinaryStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public endpoint for AI catalog documents and brochures.
 * Permitted publicly under /api/v1/public/** in SecurityConfig.
 * Used for:
 * 1. Direct browser preview (inline PDF rendering).
 * 2. Direct browser download (attachment).
 * 3. WhatsApp outbound document fetching (Meta Graph API).
 * 4. WebBot chat widget document links.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public/catalogs")
@RequiredArgsConstructor
public class PublicCatalogController {

    private final TenantAiCatalogRepository catalogRepository;
    private final CloudinaryStorageService cloudinaryStorageService;

    /**
     * GET /api/v1/public/catalogs/{id}/file?download=false
     * Streams the catalog file (PDF or image) with explicit Content-Type and Content-Disposition.
     */
    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}/file")
    public ResponseEntity<?> getPublicCatalogFile(
            @PathVariable UUID id,
            @RequestParam(value = "download", defaultValue = "false") boolean download
    ) {
        Optional<TenantAiCatalog> opt = catalogRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Catalog document not found."));
        }

        TenantAiCatalog catalog = opt.get();
        if (catalog.getStatus() == CatalogStatus.DELETED) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "This catalog document has been deleted."));
        }

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
                    ContentDisposition.builder(download ? "attachment" : "inline")
                            .filename(safeFilename)
                            .build()
            );
            headers.setContentLength(fileBytes.length);
            headers.setCacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePublic());

            return new ResponseEntity<>(fileBytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Failed to stream public catalog file {}: {}", id, e.getMessage());
            // Fallback: If byte fetch fails, redirect to direct Cloudinary URL
            if (catalog.getCloudinaryUrl() != null && !catalog.getCloudinaryUrl().isBlank()) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, catalog.getCloudinaryUrl())
                        .build();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve catalog document: " + e.getMessage()));
        }
    }
}
