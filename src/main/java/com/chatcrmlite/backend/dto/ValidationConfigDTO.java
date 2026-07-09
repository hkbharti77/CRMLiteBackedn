package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request/response body for GET and PUT /api/v1/leads/bulk-upload/validation-config.
 * Holds the tenant-level list of extra required fields beyond the defaults.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationConfigDTO {

    /** Field names (e.g. "source", "status") that must be non-blank for this tenant. */
    private List<String> extraRequiredFields;
}
