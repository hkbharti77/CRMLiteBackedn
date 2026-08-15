package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiContentResponse {
    private String subject;
    private String htmlContent;
    private String ctaLabel;
    private String ctaUrl;
}
