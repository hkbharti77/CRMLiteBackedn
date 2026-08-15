package com.chatcrmlite.backend.dto;

import lombok.*;

import java.util.List;

/**
 * DTO for admin-defined broadcast upload filter configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastFilterConfigDTO {

    /** Column names that are available as filter criteria. */
    private List<String> filterColumns;

    /** Filter rule definitions with operators and labels. */
    private List<FilterRuleDTO> filterRules;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterRuleDTO {
        private String column;
        private String operator; // EQUALS, CONTAINS, STARTS_WITH, IN, NOT_EQUALS
        private String label;   // Human-friendly label for the UI
    }
}
