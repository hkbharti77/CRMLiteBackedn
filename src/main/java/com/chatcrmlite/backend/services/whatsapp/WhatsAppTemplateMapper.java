package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.dto.WhatsAppTemplateDto;
import com.chatcrmlite.backend.models.WhatsAppTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalization & Mapping Layer for WhatsApp Message Templates.
 *
 * Provides bidirectional mapping:
 * - Meta Graph API JSON -> WhatsAppTemplateDto
 * - WhatsAppTemplate Entity <-> WhatsAppTemplateDto
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppTemplateMapper {

    private final ObjectMapper objectMapper;

    /**
     * Normalizes a raw Meta Graph API template node into WhatsAppTemplateDto.
     */
    public WhatsAppTemplateDto fromMetaJson(JsonNode tNode) {
        String name = tNode.path("name").asText();
        String language = tNode.path("language").asText("en_US");
        String category = tNode.path("category").asText("MARKETING");
        String status = tNode.path("status").asText("PENDING");
        String metaId = tNode.path("id").asText(null);
        String rejectedReason = tNode.has("rejected_reason") ? tNode.path("rejected_reason").asText() :
                tNode.has("reason") ? tNode.path("reason").asText() : null;

        String headerType = "NONE";
        String headerContent = null;
        String bodyText = "";
        String footerText = null;
        List<String> headerSamples = new ArrayList<>();
        List<String> bodySamples = new ArrayList<>();
        List<WhatsAppTemplateDto.TemplateButtonDto> buttons = new ArrayList<>();

        JsonNode components = tNode.path("components");
        if (components.isArray()) {
            for (JsonNode c : components) {
                String type = c.path("type").asText();
                if ("HEADER".equalsIgnoreCase(type)) {
                    headerType = c.path("format").asText("TEXT");
                    if ("TEXT".equalsIgnoreCase(headerType)) {
                        headerContent = c.path("text").asText(null);
                        JsonNode exampleHeader = c.path("example").path("header_text");
                        if (exampleHeader.isArray()) {
                            for (JsonNode s : exampleHeader) {
                                headerSamples.add(s.asText());
                            }
                        }
                    }
                } else if ("BODY".equalsIgnoreCase(type)) {
                    bodyText = c.path("text").asText("");
                    JsonNode exampleBody = c.path("example").path("body_text");
                    if (exampleBody.isArray() && !exampleBody.isEmpty()) {
                        JsonNode firstRow = exampleBody.get(0);
                        if (firstRow.isArray()) {
                            for (JsonNode s : firstRow) {
                                bodySamples.add(s.asText());
                            }
                        }
                    }
                } else if ("FOOTER".equalsIgnoreCase(type)) {
                    footerText = c.path("text").asText(null);
                } else if ("BUTTONS".equalsIgnoreCase(type)) {
                    JsonNode btnArray = c.path("buttons");
                    if (btnArray.isArray()) {
                        for (JsonNode b : btnArray) {
                            String btnType = b.path("type").asText();
                            String urlSample = null;
                            JsonNode exampleUrl = b.path("example");
                            if (exampleUrl.isArray() && !exampleUrl.isEmpty()) {
                                urlSample = exampleUrl.get(0).asText(null);
                            }

                            buttons.add(WhatsAppTemplateDto.TemplateButtonDto.builder()
                                    .type(btnType)
                                    .text(b.path("text").asText())
                                    .url(b.path("url").asText(null))
                                    .urlSample(urlSample)
                                    .phoneNumber(b.path("phone_number").asText(null))
                                    .flowId(b.path("flow_id").asText(null))
                                    .flowAction(b.path("flow_action").asText(null))
                                    .navigateScreen(b.path("navigate_screen").asText(null))
                                    .build());
                        }
                    }
                }
            }
        }

        return WhatsAppTemplateDto.builder()
                .id(metaId)
                .name(name)
                .language(language)
                .category(category)
                .status(status)
                .headerType(headerType)
                .headerContent(headerContent)
                .headerSampleValues(headerSamples)
                .bodyText(bodyText)
                .bodySampleValues(bodySamples)
                .footerText(footerText)
                .rejectedReason(rejectedReason)
                .buttons(buttons)
                .build();
    }

    /**
     * Converts a persisted WhatsAppTemplate entity into WhatsAppTemplateDto.
     */
    public WhatsAppTemplateDto toDto(WhatsAppTemplate t) {
        List<WhatsAppTemplateDto.TemplateButtonDto> buttons = new ArrayList<>();
        if (t.getButtonsJson() != null && !t.getButtonsJson().isBlank()) {
            try {
                buttons = objectMapper.readValue(t.getButtonsJson(), new TypeReference<List<WhatsAppTemplateDto.TemplateButtonDto>>() {});
            } catch (Exception e) {
                log.warn("[TemplateMapper] Failed to deserialize buttonsJson for template {}: {}", t.getName(), e.getMessage());
            }
        }

        return WhatsAppTemplateDto.builder()
                .id(t.getId() != null ? t.getId().toString() : null)
                .name(t.getName())
                .language(t.getLanguage())
                .category(t.getCategory())
                .status(t.getStatus())
                .headerType(t.getHeaderType())
                .headerContent(t.getHeaderContent())
                .bodyText(t.getBodyText())
                .footerText(t.getFooterText())
                .rejectedReason(t.getRejectedReason())
                .buttons(buttons)
                .build();
    }

    /**
     * Updates an existing WhatsAppTemplate entity from DTO properties.
     */
    public void updateEntity(WhatsAppTemplate entity, WhatsAppTemplateDto dto) {
        entity.setLanguage(dto.getLanguage() != null ? dto.getLanguage() : "en_US");
        entity.setCategory(dto.getCategory() != null ? dto.getCategory() : "MARKETING");
        entity.setStatus(dto.getStatus() != null ? dto.getStatus() : "PENDING");
        entity.setHeaderType(dto.getHeaderType() != null ? dto.getHeaderType() : "NONE");
        entity.setHeaderContent(dto.getHeaderContent());
        entity.setBodyText(dto.getBodyText());
        entity.setFooterText(dto.getFooterText());
        entity.setRejectedReason(dto.getRejectedReason());

        if (dto.getButtons() != null) {
            try {
                entity.setButtonsJson(objectMapper.writeValueAsString(dto.getButtons()));
            } catch (Exception ignored) {}
        }
    }
}
