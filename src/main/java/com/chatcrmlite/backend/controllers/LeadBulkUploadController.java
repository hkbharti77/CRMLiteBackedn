package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.BulkLeadRowDTO;
import com.chatcrmlite.backend.dto.BulkUploadResultDTO;
import com.chatcrmlite.backend.dto.RowErrorDTO;
import com.chatcrmlite.backend.dto.ValidationConfigDTO;
import com.chatcrmlite.backend.models.BulkUploadValidationConfig;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BulkUploadValidationConfigRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.lead.BulkLeadNotifier;
import com.chatcrmlite.backend.services.lead.BulkLeadParser;
import com.chatcrmlite.backend.services.lead.BulkLeadPersister;
import com.chatcrmlite.backend.services.lead.BulkLeadTemplateService;
import com.chatcrmlite.backend.services.lead.BulkLeadValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for the Bulk Lead Upload feature.
 *
 * <p>Exposes four endpoints:
 * <ul>
 *   <li>POST   /api/v1/leads/bulk-upload            — parse, validate, persist a file
 *   <li>GET    /api/v1/leads/bulk-upload/template   — download XLSX or CSV template
 *   <li>GET    /api/v1/leads/bulk-upload/validation-config  — get per-tenant required fields
 *   <li>PUT    /api/v1/leads/bulk-upload/validation-config  — update (OWNER/ADMIN only)
 * </ul>
 *
 * <p>Design: .kiro/specs/bulk-lead-upload/design.md §8
 */
@RestController
@RequestMapping("/api/v1/leads/bulk-upload")
public class LeadBulkUploadController {

    @Autowired private BulkLeadParser parser;
    @Autowired private BulkLeadValidator validator;
    @Autowired private BulkLeadPersister persister;
    @Autowired private BulkLeadNotifier notifier;
    @Autowired private BulkLeadTemplateService templateService;
    @Autowired private BulkUploadValidationConfigRepository validationConfigRepository;
    @Autowired private UserRepository userRepository;

    // ── Auth helper ─────────────────────────────────────────────────────────

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ── POST /api/v1/leads/bulk-upload ───────────────────────────────────────

    /**
     * Parses, validates, persists, and optionally notifies for a bulk lead file upload.
     *
     * <p>Requirements 3.3, 5; design.md §8
     *
     * @param file              multipart CSV or XLSX file
     * @param sendNotifications when true, async email is sent to each imported lead with a valid email
     * @return BulkUploadResultDTO with aggregate counts and per-row errors
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BulkUploadResultDTO> uploadLeads(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean sendNotifications) {

        User owner = getAuthenticatedUser();

        // 1. Parse — ResponseStatusException (413/422/400) propagates automatically
        List<BulkLeadRowDTO> rows = parser.parse(file);

        // 2. Load per-tenant validation config
        Optional<BulkUploadValidationConfig> configOpt =
                validationConfigRepository.findByTenantId(owner.getTenant().getId());
        List<String> extraRequired = configOpt
                .map(c -> Arrays.stream(c.getExtraFields().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toList()))
                .orElse(List.of());

        // 3. Validate
        List<RowErrorDTO> errors = new ArrayList<>();
        BulkLeadValidator.ValidationResult validationResult =
                validator.validate(rows, extraRequired, owner.getTenant().getId());
        errors.addAll(validationResult.errors());

        // 4. Persist
        List<Lead> imported = persister.persist(validationResult.validRows(), owner, errors);

        // 5. Optionally notify (async — does not block response)
        if (sendNotifications && !imported.isEmpty()) {
            notifier.notify(imported, errors);
        }

        // 6. Build result
        BulkUploadResultDTO result = BulkUploadResultDTO.builder()
                .totalRows(rows.size())
                .importedCount(imported.size())
                .skippedCount(validationResult.errors().size())
                .failedCount((int) errors.stream()
                        .filter(e -> e.getReason() != null && e.getReason().startsWith("EMAIL_SEND_FAILED"))
                        .count())
                .errors(errors)
                .build();

        return ResponseEntity.ok(result);
    }

    // ── GET /api/v1/leads/bulk-upload/template ───────────────────────────────

    /**
     * Downloads a blank lead import template as XLSX or CSV.
     *
     * <p>Requirement 2; design.md §7
     *
     * @param format "xlsx" or "csv"
     */
    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate(@RequestParam String format) {
        switch (format.toLowerCase()) {
            case "xlsx": {
                byte[] bytes = templateService.generateXlsx();
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"lead-template.xlsx\"")
                        .body(bytes);
            }
            case "csv": {
                byte[] bytes = templateService.generateCsv().getBytes();
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/csv"))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"lead-template.csv\"")
                        .body(bytes);
            }
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported format: " + format);
        }
    }

    // ── GET /api/v1/leads/bulk-upload/validation-config ──────────────────────

    /**
     * Returns the current per-tenant validation config.
     *
     * <p>Requirement 6.5; design.md §8
     */
    @GetMapping("/validation-config")
    public ResponseEntity<ValidationConfigDTO> getValidationConfig() {
        User user = getAuthenticatedUser();
        Optional<BulkUploadValidationConfig> configOpt =
                validationConfigRepository.findByTenantId(user.getTenant().getId());

        List<String> extraRequired = configOpt
                .map(c -> Arrays.stream(c.getExtraFields().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toList()))
                .orElse(List.of());

        return ResponseEntity.ok(ValidationConfigDTO.builder()
                .extraRequiredFields(extraRequired)
                .build());
    }

    // ── PUT /api/v1/leads/bulk-upload/validation-config ──────────────────────

    /**
     * Updates the per-tenant validation config. Restricted to OWNER and ADMIN roles.
     *
     * <p>Requirements 6.6, 6.7; design.md §8
     */
    @PutMapping("/validation-config")
    public ResponseEntity<ValidationConfigDTO> updateValidationConfig(
            @RequestBody ValidationConfigDTO dto) {

        User user = getAuthenticatedUser();

        // Role guard — 403 for non-admin/non-owner
        if (user.getRole() != User.Role.OWNER && user.getRole() != User.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only OWNER or ADMIN users can update the validation config");
        }

        // Join list to comma-separated string
        String extraFields = dto.getExtraRequiredFields() != null
                ? String.join(",", dto.getExtraRequiredFields())
                : "";

        // Upsert
        BulkUploadValidationConfig config =
                validationConfigRepository.findByTenantId(user.getTenant().getId())
                        .orElseGet(() -> {
                            BulkUploadValidationConfig c = new BulkUploadValidationConfig();
                            c.setTenantId(user.getTenant().getId());
                            return c;
                        });

        config.setExtraFields(extraFields);
        config.setUpdatedAt(LocalDateTime.now());
        validationConfigRepository.save(config);

        // Return the persisted state
        List<String> saved = Arrays.stream(extraFields.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

        return ResponseEntity.ok(ValidationConfigDTO.builder()
                .extraRequiredFields(saved)
                .build());
    }
}
