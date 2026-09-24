package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.journey.BulkUploadBatch;
import com.chatcrmlite.backend.repositories.journey.BulkUploadBatchRepository;
import com.chatcrmlite.backend.services.journey.BulkContactIngestionService;
import com.chatcrmlite.backend.utils.TenantResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bulk-upload")
@Slf4j
@RequiredArgsConstructor
public class BulkUploadController {

    private final BulkContactIngestionService bulkIngestionService;
    private final BulkUploadBatchRepository batchRepository;
    private final TenantResolver tenantResolver;
    private final ObjectMapper objectMapper;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadContactsFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "columnMapping", required = false) String columnMappingJson,
            @RequestParam(value = "autoTriggerJourneyId", required = false) UUID autoTriggerJourneyId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenantId,
            Authentication authentication) {

        String businessId = tenantResolver.resolveBusinessId(headerTenantId, null);
        if (businessId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tenant ID missing"));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        try {
            Map<String, String> mapping = null;
            if (columnMappingJson != null && !columnMappingJson.trim().isEmpty()) {
                mapping = objectMapper.readValue(columnMappingJson, new TypeReference<Map<String, String>>() {});
            }

            String fileName = file.getOriginalFilename();
            String fileType = "CSV";
            if (fileName != null && (fileName.endsWith(".xlsx") || fileName.endsWith(".xls"))) {
                fileType = "EXCEL";
            } else if (fileName != null && fileName.endsWith(".txt")) {
                fileType = "TXT";
            }

            String uploadedBy = authentication != null ? authentication.getName() : "API_USER";

            BulkUploadBatch batch = bulkIngestionService.initializeAndProcessBatch(
                    businessId,
                    fileName != null ? fileName : "contacts_upload.csv",
                    fileType,
                    file.getBytes(),
                    uploadedBy,
                    mapping,
                    autoTriggerJourneyId
            );

            return ResponseEntity.ok(Map.of("data", batch));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[BulkUploadController] Ingestion error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to process file upload: " + e.getMessage()));
        }
    }

    @GetMapping("/batches")
    public ResponseEntity<?> getBatches(
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenantId,
            Authentication authentication) {

        String businessId = tenantResolver.resolveBusinessId(headerTenantId, null);
        List<BulkUploadBatch> batches = batchRepository.findByBusinessId(businessId);
        return ResponseEntity.ok(Map.of("data", batches));
    }

    @GetMapping("/batches/{batchId}")
    public ResponseEntity<?> getBatchDetails(
            @PathVariable UUID batchId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenantId,
            Authentication authentication) {

        String businessId = tenantResolver.resolveBusinessId(headerTenantId, null);
        return batchRepository.findByIdAndBusinessId(batchId, businessId)
                .map(b -> ResponseEntity.ok(Map.of("data", b)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/batches/{batchId}/errors/csv")
    public ResponseEntity<String> downloadFailedRowsCsv(
            @PathVariable UUID batchId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenantId,
            Authentication authentication) {

        String businessId = tenantResolver.resolveBusinessId(headerTenantId, null);
        String csvContent = bulkIngestionService.generateFailedRowsCsv(businessId, batchId);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"failed_rows_" + batchId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csvContent);
    }
}
