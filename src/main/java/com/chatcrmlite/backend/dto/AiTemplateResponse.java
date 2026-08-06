package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTemplateResponse {
    private String subject;
    private String html;
    private boolean hasUnsubscribeLink;
}
