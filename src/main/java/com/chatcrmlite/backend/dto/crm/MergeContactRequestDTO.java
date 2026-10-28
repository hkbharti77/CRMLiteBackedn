package com.chatcrmlite.backend.dto.crm;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergeContactRequestDTO {

    @NotNull(message = "Secondary contact ID is required")
    private UUID secondaryContactId;

    /**
     * Map of field name -> "PRIMARY" | "SECONDARY"
     * Supported fields: "name", "phone", "email", "source", "marketingOptedOut", "botPaused"
     */
    private Map<String, String> fieldResolutions;

    /**
     * Strategy for resolving tags: "UNION" (default), "PRIMARY_ONLY", "SECONDARY_ONLY"
     */
    @Builder.Default
    private String tagResolution = "UNION";

    private String auditNote;
}
