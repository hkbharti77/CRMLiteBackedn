package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Describes a single row-level error or skip during bulk lead upload.
 * Included in {@link BulkUploadResultDTO#getErrors()}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RowErrorDTO {

    private int rowNumber;
    private String reason;
}
