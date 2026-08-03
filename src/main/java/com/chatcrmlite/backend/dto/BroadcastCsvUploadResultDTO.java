package com.chatcrmlite.backend.dto;

import lombok.*;

import java.util.List;
import java.util.Map;

/**
 * Response DTO returned after parsing and validating a CSV/Excel file
 * for WhatsApp broadcast audience targeting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastCsvUploadResultDTO {

    /** Total number of data rows in the file (excluding header). */
    private int totalRows;

    /** All detected column headers from the file. */
    private List<String> detectedColumns;

    /** Auto-detected phone number column name. */
    private String phoneColumnName;

    /** Number of rows with valid E.164 phone numbers. */
    private int validPhoneCount;

    /** Number of rows with invalid or missing phone numbers. */
    private int invalidPhoneCount;

    /** Number of duplicate phone numbers removed. */
    private int duplicatePhoneCount;

    /** First N sample rows for preview (column→value maps). */
    private List<Map<String, String>> sampleRows;

    /** Rows that failed phone validation (row number + reason). */
    private List<InvalidRowDTO> invalidRows;

    /** All valid parsed rows as column→value maps (for storing in campaign). */
    private List<Map<String, String>> validRows;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvalidRowDTO {
        private int rowNumber;
        private String phone;
        private String reason;
    }
}
