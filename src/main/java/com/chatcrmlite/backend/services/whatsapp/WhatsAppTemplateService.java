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

    @Autowired
    private WhatsAppTemplateMapper templateMapper;

    @Autowired
    private com.chatcrmlite.backend.services.whatsapp.validation.WhatsAppTemplateVariableParser variableParser;

    @Autowired
    private com.chatcrmlite.backend.services.whatsapp.validation.DynamicUrlValidator dynamicUrlValidator;

    @Autowired
    private com.chatcrmlite.backend.services.whatsapp.validation.FlowButtonValidator flowButtonValidator;

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

        return localTemplates.stream().map(templateMapper::toDto).collect(Collectors.toList());
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
        java.util.Set<String> activeMetaTemplateNames = new java.util.HashSet<>();

        if (dataNode.isArray()) {
            for (JsonNode tNode : dataNode) {
                WhatsAppTemplateDto metaDto = templateMapper.fromMetaJson(tNode);
                String name = metaDto.getName();
                activeMetaTemplateNames.add(name);

                Optional<WhatsAppTemplate> existingOpt = templateRepository.findByNameAndTenantId(name, tenantId);
                WhatsAppTemplate template = existingOpt.orElseGet(() -> WhatsAppTemplate.builder()
                        .name(name)
                        .owner(config.getUser() != null ? config.getUser() : currentUser)
                        .build());

                template.setMetaTemplateId(metaDto.getId());
                templateMapper.updateEntity(template, metaDto);
                templateRepository.save(template);

                dtos.add(templateMapper.toDto(template));
            }
        }

        // Clean up / prune stale templates that belonged to old credentials or were deleted on Meta
        List<WhatsAppTemplate> allLocal = templateRepository.findAllByTenantId(tenantId);
        for (WhatsAppTemplate local : allLocal) {
            if (!activeMetaTemplateNames.contains(local.getName())) {
                log.info("[TemplateService] Pruning stale template '{}' no longer present in Meta WABA {}", local.getName(), config.getWabaId());
                templateRepository.delete(local);
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

        // 1. Strict validation & resolution of variable sample values
        List<String> bodySamples = variableParser.resolveAndValidateSamples("Body", dto.getBodyText(), dto.getBodySampleValues());
        dto.setBodySampleValues(bodySamples);

        if ("TEXT".equalsIgnoreCase(dto.getHeaderType()) && dto.getHeaderContent() != null) {
            List<String> headerSamples = variableParser.resolveAndValidateSamples("Header", dto.getHeaderContent(), dto.getHeaderSampleValues());
            dto.setHeaderSampleValues(headerSamples);
        }

        // 2. Strict validation of action buttons (Flow & Dynamic URLs)
        if (dto.getButtons() != null) {
            flowButtonValidator.validateFlowButtons(dto.getButtons(), tenantId);
            for (WhatsAppTemplateDto.TemplateButtonDto btn : dto.getButtons()) {
                if (btn != null && "URL".equalsIgnoreCase(btn.getType())) {
                    dynamicUrlValidator.validateUrl(btn.getUrl(), btn.getUrlSample());
                }
            }
        }

        // 3. Submit to Meta Graph API
        JsonNode metaResult = metaWhatsAppClient.createMessageTemplate(config.getWabaId(), dto, config.getAccessToken());
        String metaId = metaResult.path("id").asText(null);
        String status = metaResult.path("status").asText("PENDING");
        dto.setStatus(status);

        // 4. Normalized Persistence
        WhatsAppTemplate template = templateRepository.findByNameAndTenantId(dto.getName(), tenantId)
                .orElseGet(() -> WhatsAppTemplate.builder()
                        .name(dto.getName())
                        .owner(config.getUser() != null ? config.getUser() : currentUser)
                        .build());

        template.setMetaTemplateId(metaId);
        templateMapper.updateEntity(template, dto);
        WhatsAppTemplate saved = templateRepository.save(template);

        return templateMapper.toDto(saved);
    }

    @Transactional
    public WhatsAppTemplateDto updateTemplate(String name, WhatsAppTemplateDto dto, User currentUser) {
        UUID tenantId = currentUser.getTenant().getId();
        WhatsAppConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant: " + tenantId));

        if (config.getWabaId() == null || config.getWabaId().isBlank() || config.getAccessToken() == null || config.getAccessToken().isBlank()) {
            throw new IllegalStateException("WABA ID and Access Token must be configured before editing templates.");
        }

        WhatsAppTemplate template = templateRepository.findByNameAndTenantId(name, tenantId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Template not found: " + name));

        // Preserve immutable attributes per Meta Policy
        dto.setName(name);
        if (template.getLanguage() != null && !template.getLanguage().isBlank()) {
            dto.setLanguage(template.getLanguage());
        }

        // 1. Strict validation & resolution of variable sample values
        List<String> bodySamples = variableParser.resolveAndValidateSamples("Body", dto.getBodyText(), dto.getBodySampleValues());
        dto.setBodySampleValues(bodySamples);

        if ("TEXT".equalsIgnoreCase(dto.getHeaderType()) && dto.getHeaderContent() != null) {
            List<String> headerSamples = variableParser.resolveAndValidateSamples("Header", dto.getHeaderContent(), dto.getHeaderSampleValues());
            dto.setHeaderSampleValues(headerSamples);
        }

        // 2. Strict validation of action buttons (Flow & Dynamic URLs)
        if (dto.getButtons() != null) {
            flowButtonValidator.validateFlowButtons(dto.getButtons(), tenantId);
            for (WhatsAppTemplateDto.TemplateButtonDto btn : dto.getButtons()) {
                if (btn != null && "URL".equalsIgnoreCase(btn.getType())) {
                    dynamicUrlValidator.validateUrl(btn.getUrl(), btn.getUrlSample());
                }
            }
        }

        // 3. Submit Update to Meta Graph API
        String metaTemplateId = template.getMetaTemplateId();
        String status = "PENDING";
        if (metaTemplateId != null && !metaTemplateId.isBlank()) {
            JsonNode metaResult = metaWhatsAppClient.updateMessageTemplate(metaTemplateId, dto, config.getAccessToken());
            status = metaResult.path("status").asText("PENDING");
        } else {
            // Fallback if template was synced without ID or legacy: re-create on Meta
            JsonNode metaResult = metaWhatsAppClient.createMessageTemplate(config.getWabaId(), dto, config.getAccessToken());
            metaTemplateId = metaResult.path("id").asText(null);
            status = metaResult.path("status").asText("PENDING");
            template.setMetaTemplateId(metaTemplateId);
        }
        dto.setStatus(status);

        // 4. Update and Persist Entity
        templateMapper.updateEntity(template, dto);
        template.setStatus(status);
        WhatsAppTemplate saved = templateRepository.save(template);
        log.info("✅ [TemplateService] Updated template '{}' (status={}) for tenant {}", name, status, tenantId);

        return templateMapper.toDto(saved);
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
                String reason = e.getReason() != null ? e.getReason().toLowerCase() : "";
                // If template not found on Meta account (e.g. from previous account or already deleted on Meta), proceed to delete locally
                if (reason.contains("wasn't found") || reason.contains("not found") || reason.contains("2593002") || reason.contains("100")) {
                    log.warn("[TemplateService] Template '{}' not found in Meta WABA {}. Removing from local database.", name, config.getWabaId());
                } else {
                    log.error("[TemplateService] Meta API delete for template '{}' refused: {}", name, e.getReason());
                    throw e;
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                if (msg.contains("wasn't found") || msg.contains("not found") || msg.contains("2593002")) {
                    log.warn("[TemplateService] Template '{}' not found in Meta WABA {}. Removing from local database.", name, config.getWabaId());
                } else {
                    log.error("[TemplateService] Meta API delete for template '{}' failed: {}", name, e.getMessage());
                    throw new RuntimeException("Meta API Delete Failed: " + e.getMessage());
                }
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
            "You are an expert multilingual WhatsApp marketing copywriter and Meta WhatsApp Business Platform specialist.\n" +
            "Generate a WhatsApp Template adhering strictly to Meta's formatting, category, language, and character limit rules.\n\n" +
            "Rules:\n" +
            "0. Name: Required. Maximum 512 characters. Unique, concise, Romanized lowercase alphanumeric characters and underscores ONLY (e.g. 'summer_sale_offer_v1', 'order_status_update_hi_v1', 'appointment_reminder_v1'). NO spaces, NO uppercase, NO non-Latin script (even if template body is in Hindi/Arabic/Spanish, the name MUST be Romanized lowercase English [a-z0-9_]).\n" +
            "1. Category: Intelligent auto-classification. One of 'MARKETING', 'UTILITY', 'AUTHENTICATION':\n" +
            "   - 'MARKETING': Promotions, discounts, festival offers, product launches, event invitations, greetings.\n" +
            "   - 'UTILITY': Transactional updates, order confirmations, shipment tracking, booking reminders, invoice alerts.\n" +
            "   - 'AUTHENTICATION': One-time passcodes, login verifications, security alerts.\n" +
            "2. Language: Standard Meta language code matching the template content (e.g. 'hi' for Hindi, 'en_US' for English, 'es' for Spanish, 'pt_BR' for Portuguese, 'ar' for Arabic, 'fr' for French, 'de' for German, 'id' for Indonesian, 'it' for Italian, 'bn' for Bengali, 'ta' for Tamil, 'te' for Telugu, 'mr' for Marathi, 'gu' for Gujarati).\n" +
            "3. Header: Maximum 60 characters. Text only. In the requested language.\n" +
            "4. Body: Maximum 1024 characters. In the requested language. Use only WhatsApp formatting: *bold*, _italic_, ~strikethrough~. Do NOT use markdown headers or HTML. Use sequential numeric variables like {{1}}, {{2}} for dynamic customer/order placeholders.\n" +
            "5. Footer: Maximum 60 characters. Text only. In the requested language.\n" +
            "6. Buttons: Max 3 buttons. Allowed types: QUICK_REPLY, URL, PHONE_NUMBER. Text in the requested language (max 25 chars per button).\n\n" +
            "Output MUST be a valid JSON object with EXACTLY these keys:\n" +
            "{\n" +
            "  \"name\": \"summer_sale_promo_v1\",\n" +
            "  \"category\": \"MARKETING\",\n" +
            "  \"language\": \"hi\",\n" +
            "  \"headerContent\": \"...\",\n" +
            "  \"bodyText\": \"...\",\n" +
            "  \"footerText\": \"...\",\n" +
            "  \"buttons\": [\n" +
            "    { \"type\": \"QUICK_REPLY\", \"text\": \"अभी खरीदें\" },\n" +
            "    { \"type\": \"URL\", \"text\": \"वेबसाइट देखें\", \"url\": \"https://example.com\" }\n" +
            "  ]\n" +
            "}";

        if (chatLanguageModel == null) {
            log.warn("ChatLanguageModel not configured. Returning fallback WhatsApp AI template.");
            boolean isHindi = prompt.matches(".*[\\u0900-\\u097F].*") || prompt.toLowerCase().contains("hindi");
            return WhatsAppAiTemplateResponse.builder()
                .name((isHindi ? "vishesh_offer_hi_" : "promo_campaign_") + (System.currentTimeMillis() % 100000))
                .category("MARKETING")
                .language(isHindi ? "hi" : "en_US")
                .headerContent(isHindi ? "विशेष ऑफर आपके लिए!" : "Special Offer Inside!")
                .bodyText(isHindi 
                    ? "नमस्ते {{1}},\n\nआपके लिए खास पेशकश: *" + prompt.replace("\"", "") + "*\n\nऑफर का लाभ उठाने के लिए नीचे दिए गए बटन पर क्लिक करें।"
                    : "Hi {{1}},\n\nHere is your custom template for: _" + prompt.replace("\"", "") + "_\n\nReply STOP to unsubscribe.")
                .footerText(isHindi ? "टीम ज्ञानवाणी" : "Company Name")
                .buttons(List.of(
                    WhatsAppTemplateDto.TemplateButtonDto.builder().type("QUICK_REPLY").text(isHindi ? "रुचि है" : "Interested").build(),
                    WhatsAppTemplateDto.TemplateButtonDto.builder().type("URL").text(isHindi ? "विवरण देखें" : "View Details").url("https://example.com").build()
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

            WhatsAppAiTemplateResponse templateResponse = objectMapper.readValue(jsonText, WhatsAppAiTemplateResponse.class);
            if (templateResponse.getName() != null && !templateResponse.getName().isBlank()) {
                String sanitized = templateResponse.getName().toLowerCase().replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
                templateResponse.setName(sanitized.isBlank() ? "campaign_" + (System.currentTimeMillis() % 100000) : sanitized);
            } else {
                templateResponse.setName("campaign_" + (System.currentTimeMillis() % 100000));
            }

            if (templateResponse.getLanguage() == null || templateResponse.getLanguage().isBlank()) {
                boolean hasDevanagari = (templateResponse.getBodyText() != null && templateResponse.getBodyText().matches(".*[\\u0900-\\u097F].*"))
                        || prompt.matches(".*[\\u0900-\\u097F].*") || prompt.toLowerCase().contains("hindi");
                templateResponse.setLanguage(hasDevanagari ? "hi" : "en_US");
            }

            if (templateResponse.getCategory() == null || templateResponse.getCategory().isBlank()) {
                templateResponse.setCategory("MARKETING");
            }

            return templateResponse;
        } catch (Exception e) {
            log.error("Failed to generate AI WhatsApp Template: ", e);
            throw new RuntimeException("AI generation failed: " + e.getMessage());
        }
    }
}
