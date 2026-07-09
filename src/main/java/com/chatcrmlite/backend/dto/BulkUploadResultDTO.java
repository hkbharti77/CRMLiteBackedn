package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Summary result returned by POST /api/v1/leads/bulk-upload.
 * Contains aggregate counts and per-row errors for the frontend result screen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkUploadResultDTO {

    private int totalRows;
    private int importedCount;
    private int skippedCount;
    private int failedCount;

    /** Per-row validation failures and email-send failures. */
    private List<RowErrorDTO> errors;
}
