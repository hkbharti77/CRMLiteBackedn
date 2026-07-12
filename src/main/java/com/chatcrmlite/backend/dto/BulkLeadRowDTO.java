package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Internal parse target for a single row in a bulk lead upload file.
 * Preserves the original row number for error reporting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkLeadRowDTO {

    private int rowNumber;

    private String name;
    private String email;
    private String phone;
    private String source;
    private String status;
    private String notes;
    private String tags;
}
