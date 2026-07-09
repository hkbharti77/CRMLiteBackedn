package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.dto.BulkLeadRowDTO;
import com.chatcrmlite.backend.dto.RowErrorDTO;
import com.chatcrmlite.backend.repositories.ContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Validates rows parsed from a bulk lead upload file.
 * Applies ordered validation rules and separates rows into valid and errored buckets.
 */
@Service
@RequiredArgsConstructor
public class BulkLeadValidator {

    private final ContactRepository contactRepository;

    /**
     * Validates a list of parsed rows against standard and tenant-specific rules.
     *
     * @param rows               parsed rows from the uploaded file
     * @param extraRequiredFields additional field names that must be non-blank (may be null/empty)
     * @param tenantId           the tenant context used for duplicate-email checks
     * @return a {@link ValidationResult} containing valid rows and per-row errors
     */
    public ValidationResult validate(List<BulkLeadRowDTO> rows,
                                     List<String> extraRequiredFields,
                                     UUID tenantId) {
        List<BulkLeadRowDTO> validRows = new ArrayList<>();
        List<RowErrorDTO> errors = new ArrayList<>();

        for (BulkLeadRowDTO row : rows) {

            // Rule 1: name must be non-blank
            if (isBlank(row.getName())) {
                errors.add(new RowErrorDTO(row.getRowNumber(), "name is required"));
                continue;
            }

            // Rule 2: at least one of email or phone must be non-blank
            if (isBlank(row.getEmail()) && isBlank(row.getPhone())) {
                errors.add(new RowErrorDTO(row.getRowNumber(), "email or phone is required"));
                continue;
            }

            // Rule 3: extra required fields must each be non-blank
            if (extraRequiredFields != null && !extraRequiredFields.isEmpty()) {
                boolean failedExtra = false;
                for (String fieldName : extraRequiredFields) {
                    if (isBlank(getFieldValue(row, fieldName))) {
                        errors.add(new RowErrorDTO(row.getRowNumber(), fieldName + " is required"));
                        failedExtra = true;
                        break;
                    }
                }
                if (failedExtra) {
                    continue;
                }
            }

            // Rule 4: duplicate email check
            if (!isBlank(row.getEmail())) {
                if (contactRepository.existsByEmailAndTenant_Id(row.getEmail(), tenantId)) {
                    errors.add(new RowErrorDTO(row.getRowNumber(), "duplicate email"));
                    continue;
                }
            }

            validRows.add(row);
        }

        return new ValidationResult(validRows, errors);
    }

    /**
     * Maps a field name string to the corresponding getter on {@link BulkLeadRowDTO}.
     * Supports: source, status, notes, tags.
     */
    private String getFieldValue(BulkLeadRowDTO row, String fieldName) {
        return switch (fieldName.toLowerCase()) {
            case "source" -> row.getSource();
            case "status" -> row.getStatus();
            case "notes"  -> row.getNotes();
            case "tags"   -> row.getTags();
            default       -> null;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Result of a bulk validation pass.
     *
     * @param validRows rows that passed all validation checks
     * @param errors    per-row error descriptors for rows that failed
     */
    public record ValidationResult(List<BulkLeadRowDTO> validRows, List<RowErrorDTO> errors) {}
}
