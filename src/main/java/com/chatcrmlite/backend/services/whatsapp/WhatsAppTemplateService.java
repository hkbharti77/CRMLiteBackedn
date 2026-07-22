package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.WhatsAppTemplateDto;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.WhatsAppTemplate;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppTemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppTemplateService {

    private final WhatsAppTemplateRepository templateRepository;
    private final WhatsAppConfigRepository configRepository;
    private final MetaWhatsAppClient metaWhatsAppClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<WhatsAppTemplateDto> getTemplatesForTenant(User currentUser, boolean forceSync) {
        UUID tenantId = currentUser.getTenant().getId();
        List<WhatsAppTemplate> localTemplates = templateRepository.findAllByTenantId(tenantId);

        if (forceSync || localTemplates.isEmpty()) {
            try {
                return syncTemplatesFromMeta(currentUser);
            } catch (Exception e) {
                log.warn("[TemplateService] Failed to sync live templates from Meta Graph API for tenant {}: {}", tenantId, e.getMessage());
            }
        }

        return localTemplates.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public List<WhatsAppTemplateDto> syncTemplatesFromMeta(User currentUser) {
        UUID tenantId = currentUser.getTenant().getId();
        WhatsAppConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant: " + tenantId));

        if (config.getWabaId() == null || config.getWabaId().isBlank() || config.getAccessToken() == null || config.getAccessToken().isBlank()) {
            throw new IllegalStateException("WABA ID and Access Token must be configured before syncing templates.");
        }

        JsonNode metaResponse = metaWhatsAppClient.fetchMessageTemplates(config.getWabaId(), config.getAccessToken());
        JsonNode dataNode = metaResponse.path("data");

        List<WhatsAppTemplateDto> dtos = new ArrayList<>();

        if (dataNode.isArray()) {
            for (JsonNode tNode : dataNode) {
                String name = tNode.path("name").asText();
                String language = tNode.path("language").asText();
                String category = tNode.path("category").asText();
                String status = tNode.path("status").asText();
                String metaId = tNode.path("id").asText();
                String rejectedReason = tNode.has("rejected_reason") ? tNode.path("rejected_reason").asText() : 
                                       tNode.has("reason") ? tNode.path("reason").asText() : null;

                String headerType = "NONE";
                String headerContent = null;
                String bodyText = "";
                String footerText = null;
                List<WhatsAppTemplateDto.TemplateButtonDto> buttons = new ArrayList<>();

                JsonNode components = tNode.path("components");
                if (components.isArray()) {
                    for (JsonNode c : components) {
                        String type = c.path("type").asText();
                        if ("HEADER".equalsIgnoreCase(type)) {
                            headerType = c.path("format").asText("TEXT");
                            if ("TEXT".equalsIgnoreCase(headerType)) {
                                headerContent = c.path("text").asText();
                            }
                        } else if ("BODY".equalsIgnoreCase(type)) {
                            bodyText = c.path("text").asText();
                        } else if ("FOOTER".equalsIgnoreCase(type)) {
                            footerText = c.path("text").asText();
                        } else if ("BUTTONS".equalsIgnoreCase(type)) {
                            JsonNode btnArray = c.path("buttons");
                            if (btnArray.isArray()) {
                                for (JsonNode b : btnArray) {
                                    buttons.add(WhatsAppTemplateDto.TemplateButtonDto.builder()
                                            .type(b.path("type").asText())
                                            .text(b.path("text").asText())
                                            .url(b.path("url").asText(null))
                                            .phoneNumber(b.path("phone_number").asText(null))
                                            .build());
                                }
                            }
                        }
                    }
                }

                Optional<WhatsAppTemplate> existingOpt = templateRepository.findByNameAndTenantId(name, tenantId);
                WhatsAppTemplate template = existingOpt.orElseGet(() -> WhatsAppTemplate.builder()
                        .name(name)
                        .owner(config.getUser() != null ? config.getUser() : currentUser)
                        .build());

                template.setLanguage(language);
                template.setCategory(category);
                template.setStatus(status);
                template.setHeaderType(headerType);
                template.setHeaderContent(headerContent);
                template.setBodyText(bodyText);
                template.setFooterText(footerText);
                template.setMetaTemplateId(metaId);
                template.setRejectedReason(rejectedReason);

                try {
                    template.setButtonsJson(objectMapper.writeValueAsString(buttons));
                } catch (Exception ignored) {}

                templateRepository.save(template);
                
                WhatsAppTemplateDto dto = toDto(template);
                dto.setButtons(buttons);
                dtos.add(dto);
            }
        }

        return dtos;
    }

    @Transactional
    public WhatsAppTemplateDto createAndSubmitTemplate(WhatsAppTemplateDto dto, User currentUser) {
        UUID tenantId = currentUser.getTenant().getId();
        WhatsAppConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant: " + tenantId));

        if (config.getWabaId() == null || config.getWabaId().isBlank() || config.getAccessToken() == null || config.getAccessToken().isBlank()) {
            throw new IllegalStateException("WABA ID and Access Token must be configured before submitting templates.");
        }

        JsonNode metaResult = metaWhatsAppClient.createMessageTemplate(config.getWabaId(), dto, config.getAccessToken());
        String metaId = metaResult.path("id").asText(null);
        String status = metaResult.path("status").asText("PENDING");

        WhatsAppTemplate template = WhatsAppTemplate.builder()
                .name(dto.getName())
                .language(dto.getLanguage() != null ? dto.getLanguage() : "en_US")
                .category(dto.getCategory() != null ? dto.getCategory() : "MARKETING")
                .status(status)
                .headerType(dto.getHeaderType() != null ? dto.getHeaderType() : "NONE")
                .headerContent(dto.getHeaderContent())
                .bodyText(dto.getBodyText())
                .footerText(dto.getFooterText())
                .metaTemplateId(metaId)
                .owner(config.getUser() != null ? config.getUser() : currentUser)
                .build();

        try {
            if (dto.getButtons() != null) {
                template.setButtonsJson(objectMapper.writeValueAsString(dto.getButtons()));
            }
        } catch (Exception ignored) {}

        WhatsAppTemplate saved = templateRepository.save(template);
        WhatsAppTemplateDto result = toDto(saved);
        result.setButtons(dto.getButtons());
        return result;
    }

    @Transactional
    public void deleteTemplate(String name, User currentUser) {
        UUID tenantId = currentUser.getTenant().getId();
        WhatsAppConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant: " + tenantId));

        if (config.getWabaId() != null && config.getAccessToken() != null) {
            try {
                metaWhatsAppClient.deleteMessageTemplate(config.getWabaId(), name, config.getAccessToken());
                log.info("✅ [TemplateService] Successfully deleted template '{}' from Meta WABA {}", name, config.getWabaId());
            } catch (Exception e) {
                log.error("[TemplateService] Meta API delete for template '{}' failed: {}", name, e.getMessage());
                // Throw exception so user knows why Meta refused to delete (e.g. system default template)
                throw new RuntimeException("Meta API Delete Failed: " + e.getMessage());
            }
        }

        templateRepository.findByNameAndTenantId(name, tenantId).ifPresent(templateRepository::delete);
    }

    private WhatsAppTemplateDto toDto(WhatsAppTemplate t) {
        List<WhatsAppTemplateDto.TemplateButtonDto> buttons = new ArrayList<>();
        if (t.getButtonsJson() != null && !t.getButtonsJson().isBlank()) {
            try {
                buttons = objectMapper.readValue(t.getButtonsJson(), 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, WhatsAppTemplateDto.TemplateButtonDto.class));
            } catch (Exception ignored) {}
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
}
