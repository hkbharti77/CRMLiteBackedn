package com.chatcrmlite.backend.dto.payments;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableTemplateDto {
    private String definitionKey;
    private int version;
    private String metaTemplateName;
    private String language;
    private String expectedCategory;
    private String actualCategory;
    private String status; // APPROVED, PENDING, REJECTED, PAUSED, DISABLED, FLAGGED
    private boolean sendEnabled;
    private String description;
    private List<String> requiredVariables;
    private String sampleBody;
}
