package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.WhatsAppTemplateDto;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.WhatsAppTemplate;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppTemplateRepository;
import com.chatcrmlite.backend.dto.WhatsAppAiTemplateResponse;
import com.chatcrmlite.backend.services.AIQuotaService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired(required = false)
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private AIQuotaService aiQuotaService;

    @Autowired
    private com.chatcrmlite.backend.services.tenant.TenantTierService tenantTierService;

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
            } catch (org.springframework.web.server.ResponseStatusException e) {
                log.error("[TemplateService] Meta API delete for template '{}' refused: {}", name, e.getReason());
                throw e;
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

    public WhatsAppAiTemplateResponse generateAiTemplate(User user, String prompt) {
        User.PlanType plan = User.PlanType.FREE;
        if (user.getRole() == User.Role.SUPER_ADMIN) {
            plan = User.PlanType.ENTERPRISE;
        } else if (user.getTenant() != null && user.getTenant().getId() != null) {
            plan = tenantTierService.getTier(user.getTenant().getId());
        } else if (user.getPlanType() != null) {
            plan = user.getPlanType();
        }
        aiQuotaService.checkAndEnforceQuota(user.getTenant() != null ? user.getTenant().getId() : user.getId(), plan);

        String systemInstruction = 
            "You are an expert WhatsApp marketing copywriter. " +
            "Generate a WhatsApp Template adhering strictly to Meta's formatting and character limit rules.\n" +
            "Rules:\n" +
            "1. Header: Maximum 60 characters. Text only.\n" +
            "2. Body: Maximum 1024 characters. Use only WhatsApp formatting: *bold*, _italic_, ~strikethrough~. Do NOT use markdown headers or HTML. Use numeric variables like {{1}}, {{2}} for dynamic data.\n" +
            "3. Footer: Maximum 60 characters. Text only.\n" +
            "4. Buttons: Max 3 buttons. Allowed types: QUICK_REPLY, URL, PHONE_NUMBER. If the user doesn't specify URLs or Phone numbers, provide placeholders like 'https://example.com' or '+1234567890'.\n\n" +
            "Output MUST be a valid JSON object with EXACTLY these keys:\n" +
            "{\n" +
            "  \"headerContent\": \"...\",\n" +
            "  \"bodyText\": \"...\",\n" +
            "  \"footerText\": \"...\",\n" +
            "  \"buttons\": [\n" +
            "    { \"type\": \"QUICK_REPLY\", \"text\": \"Buy Now\" },\n" +
            "    { \"type\": \"URL\", \"text\": \"Visit Site\", \"url\": \"https://example.com\" }\n" +
            "  ]\n" +
            "}";

        if (chatLanguageModel == null) {
            log.warn("ChatLanguageModel not configured. Returning fallback WhatsApp AI template.");
            return WhatsAppAiTemplateResponse.builder()
                .headerContent("Special Offer Inside!")
                .bodyText("Hi {{1}},\n\nHere is your custom template for: _" + prompt.replace("\"", "") + "_\n\nReply STOP to unsubscribe.")
                .footerText("Company Name")
                .buttons(List.of(
                    WhatsAppTemplateDto.TemplateButtonDto.builder().type("QUICK_REPLY").text("Interested").build(),
                    WhatsAppTemplateDto.TemplateButtonDto.builder().type("URL").text("View Details").url("https://example.com").build()
                ))
                .build();
        }

        try {
            Response<AiMessage> response = chatLanguageModel.generate(
                new SystemMessage(systemInstruction),
                new UserMessage(prompt)
            );

            String jsonText = response.content().text().trim();
            if (jsonText.startsWith("```json")) {
                jsonText = jsonText.substring(7);
            } else if (jsonText.startsWith("```")) {
                jsonText = jsonText.substring(3);
            }
            if (jsonText.endsWith("```")) {
                jsonText = jsonText.substring(0, jsonText.length() - 3);
            }
            jsonText = jsonText.trim();

            return objectMapper.readValue(jsonText, WhatsAppAiTemplateResponse.class);
        } catch (Exception e) {
            log.error("Failed to generate AI WhatsApp Template: ", e);
            throw new RuntimeException("AI generation failed: " + e.getMessage());
        }
    }
}
