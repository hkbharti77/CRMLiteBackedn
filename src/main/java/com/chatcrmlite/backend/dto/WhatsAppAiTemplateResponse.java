package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppAiTemplateResponse {
    private String name;
    private String category;
    private String language;
    private String headerContent;
    private String bodyText;
    private String footerText;
    private List<WhatsAppTemplateDto.TemplateButtonDto> buttons;
}
