package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVariantInput {
    private String variantCode; // 'A', 'B'
    private String name;        // e.g. "Variant A (Curiosity Hook)"
    private String subject;     // e.g. "Quick question about your property"
    private String body;        // HTML content
    private String builderJson; // Visual modular block JSON
}
