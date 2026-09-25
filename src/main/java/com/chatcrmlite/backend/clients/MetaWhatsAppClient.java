package com.chatcrmlite.backend.clients;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatcrmlite.backend.dto.MenuDto;
import com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppRecipientResolver;
import com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppRecipientResolver.ResolvedRecipient;
import lombok.extern.slf4j.Slf4j;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@CircuitBreaker(name = "whatsappClient")
public class MetaWhatsAppClient implements WhatsAppClient {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired(required = false)
    private com.chatcrmlite.backend.services.whatsapp.validation.WhatsAppTemplateVariableParser variableParser;

    @Autowired(required = false)
    private com.chatcrmlite.backend.services.whatsapp.validation.DynamicUrlValidator dynamicUrlValidator;

    @org.springframework.beans.factory.annotation.Value("${meta.api-base-url:https://graph.facebook.com}")
    private String apiBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${meta.graph-api-version:v21.0}")
    private String apiVersion;

    private String getGraphBaseUrl() {
        String base = (apiBaseUrl != null && !apiBaseUrl.isBlank()) ? apiBaseUrl.replaceAll("/+$", "") : "https://graph.facebook.com";
        String ver = (apiVersion != null && !apiVersion.isBlank()) ? apiVersion.trim() : "v21.0";
        if (!ver.startsWith("v")) ver = "v" + ver;
        return base + "/" + ver;
    }

    public Map<String, Object> buildHeaderExample(String headerText, List<String> headerSamples) {
        if (variableParser == null || headerText == null) return null;
        List<String> samples = variableParser.resolveAndValidateSamples("Header", headerText, headerSamples);
        if (samples.isEmpty()) return null;
        Map<String, Object> example = new HashMap<>();
        example.put("header_text", samples);
        return example;
    }

    public Map<String, Object> buildBodyExample(String bodyText, List<String> bodySamples) {
        if (variableParser == null || bodyText == null) return null;
        List<String> samples = variableParser.resolveAndValidateSamples("Body", bodyText, bodySamples);
        if (samples.isEmpty()) return null;
        Map<String, Object> example = new HashMap<>();
        example.put("body_text", List.of(samples));
        return example;
    }

    public List<String> buildButtonExample(com.chatcrmlite.backend.dto.WhatsAppTemplateDto.TemplateButtonDto btn) {
        if (btn == null || !"URL".equalsIgnoreCase(btn.getType()) || btn.getUrl() == null) return null;
        if (dynamicUrlValidator != null && dynamicUrlValidator.isDynamicUrl(btn.getUrl())) {
            dynamicUrlValidator.validateUrl(btn.getUrl(), btn.getUrlSample());
            return List.of(btn.getUrlSample().trim());
        }
        return null;
    }

    private static final String META_URL = "https://graph.facebook.com/v18.0/%s/messages";

    @Override
    public String sendMessage(String to, String text, String accessToken, String phoneNumberId) {
        String url = String.format(META_URL, phoneNumberId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "text");

        Map<String, String> textBody = new HashMap<>();
        textBody.put("body", text);
        body.put("text", textBody);

        return executeApiCallWithRetry(url, headers, body);
    }

    @Override
    public String sendMessageToRecipient(String phoneNumber, String bsuid, String text, String accessToken, String phoneNumberId) {
        return sendMessageToRecipient(phoneNumber, bsuid, null, text, accessToken, phoneNumberId);
    }

    @Override
    public String sendMessageToRecipient(String phoneNumber, String bsuid, String parentBsuid, String text, String accessToken, String phoneNumberId) {
        WhatsAppRecipientResolver.RecipientIdentityType type = WhatsAppRecipientResolver.RecipientIdentityType.UNSENDABLE;
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            type = WhatsAppRecipientResolver.RecipientIdentityType.PHONE;
        } else if (bsuid != null && !bsuid.isBlank()) {
            type = WhatsAppRecipientResolver.RecipientIdentityType.BSUID;
        } else if (parentBsuid != null && !parentBsuid.isBlank()) {
            type = WhatsAppRecipientResolver.RecipientIdentityType.PARENT_BSUID;
        }
        return sendMessageToRecipient(new WhatsAppRecipientResolver.ResolvedRecipient(phoneNumber, bsuid, parentBsuid, type), text, accessToken, phoneNumberId);
    }

    @Override
    public String sendMessageToRecipient(com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppRecipientResolver.ResolvedRecipient recipient, String text, String accessToken, String phoneNumberId) {
        if (recipient == null || !recipient.isSendable()) {
            throw new IllegalArgumentException("Cannot send message to unsendable recipient");
        }
        String url = String.format(META_URL, phoneNumberId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");

        if (recipient.identityType() == WhatsAppRecipientResolver.RecipientIdentityType.PHONE) {
            body.put("to", recipient.value());
        } else if (recipient.identityType() == WhatsAppRecipientResolver.RecipientIdentityType.BSUID
                || recipient.identityType() == WhatsAppRecipientResolver.RecipientIdentityType.PARENT_BSUID) {
            body.put("recipient", recipient.value());
        }

        body.put("type", "text");
        Map<String, String> textBody = new HashMap<>();
        textBody.put("body", text);
        body.put("text", textBody);

        return executeApiCallWithRetry(url, headers, body);
    }

    @Override
    public String sendImage(String to, String imageUrl, String caption, String accessToken, String phoneNumberId) {
        String url = String.format(META_URL, phoneNumberId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "image");

        Map<String, String> imageBody = new HashMap<>();
        imageBody.put("link", imageUrl);
        if (caption != null && !caption.isBlank()) {
            imageBody.put("caption", caption);
        }
        body.put("image", imageBody);

        return executeApiCallWithRetry(url, headers, body);
    }

    @Override
    public String sendDocument(String to, String documentUrl, String fileName, String caption, String accessToken, String phoneNumberId) {
        String url = String.format(META_URL, phoneNumberId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "document");

        Map<String, String> documentBody = new HashMap<>();
        documentBody.put("link", documentUrl);
        if (fileName != null && !fileName.isBlank()) {
            documentBody.put("filename", fileName);
        }
        if (caption != null && !caption.isBlank()) {
            documentBody.put("caption", caption);
        }
        body.put("document", documentBody);

        return executeApiCallWithRetry(url, headers, body);
    }

    public String sendInteractiveObject(String phoneNumberId, String accessToken, Object payload) {
        String url = getGraphBaseUrl() + "/" + phoneNumberId + "/messages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (payload instanceof Map)
            ? (Map<String, Object>) payload
            : objectMapper.convertValue(payload, Map.class);

        return executeApiCallWithRetry(url, headers, body);
    }

    public String sendInteractiveMenu(String to, MenuDto menu, String accessToken, String phoneNumberId) {
        String url = String.format(META_URL, phoneNumberId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        boolean isButton = "button".equals(menu.getType());

        // Build Meta API structure for interactive message
        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "interactive");

        Map<String, Object> interactive = new HashMap<>();
        interactive.put("type", isButton ? "button" : "list");

        // Header and Body text (Document, Video, Image, or Text)
        if (menu.getHeaderDocumentUrl() != null && !menu.getHeaderDocumentUrl().isBlank()) {
            Map<String, Object> headerObj = new HashMap<>();
            headerObj.put("type", "document");
            Map<String, String> docObj = new HashMap<>();
            docObj.put("link", menu.getHeaderDocumentUrl());
            if (menu.getHeaderDocumentFilename() != null && !menu.getHeaderDocumentFilename().isBlank()) {
                docObj.put("filename", menu.getHeaderDocumentFilename());
            }
            headerObj.put("document", docObj);
            interactive.put("header", headerObj);
        } else if (menu.getHeaderVideoUrl() != null && !menu.getHeaderVideoUrl().isBlank()) {
            Map<String, Object> headerObj = new HashMap<>();
            headerObj.put("type", "video");
            Map<String, String> vidObj = new HashMap<>();
            vidObj.put("link", menu.getHeaderVideoUrl());
            headerObj.put("video", vidObj);
            interactive.put("header", headerObj);
        } else if (menu.getHeaderImageUrl() != null && !menu.getHeaderImageUrl().isBlank()) {
            Map<String, Object> headerObj = new HashMap<>();
            headerObj.put("type", "image");
            Map<String, String> imgObj = new HashMap<>();
            imgObj.put("link", menu.getHeaderImageUrl());
            headerObj.put("image", imgObj);
            interactive.put("header", headerObj);
        } else if (menu.getTitle() != null && !menu.getTitle().isEmpty()) {
            Map<String, Object> headerObj = new HashMap<>();
            headerObj.put("type", "text");
            headerObj.put("text", menu.getTitle());
            interactive.put("header", headerObj);
        }

        Map<String, Object> bodyObj = new HashMap<>();
        String bodyText = (menu.getBodyText() != null && !menu.getBodyText().isBlank())
                ? menu.getBodyText()
                : "Please choose an option below:";
        bodyObj.put("text", bodyText);
        interactive.put("body", bodyObj);

        Map<String, Object> action = new HashMap<>();

        if (isButton) {
            List<Map<String, Object>> buttonsBody = new ArrayList<>();
            if (menu.getSections() != null && !menu.getSections().isEmpty()) {
                MenuDto.MenuSectionDto sec = menu.getSections().get(0);
                if (sec.getRows() != null) {
                    for (MenuDto.MenuRowDto row : sec.getRows()) {
                        if (row == null) continue;
                        Map<String, Object> btnMap = new HashMap<>();
                        btnMap.put("type", "reply");
                        Map<String, Object> replyMap = new HashMap<>();
                        replyMap.put("id", row.getId() != null ? row.getId() : "btn_" + buttonsBody.size());
                        // WhatsApp button title: max 20 chars
                        String title = row.getTitle() != null ? row.getTitle() : "Option";
                        if (title.length() > 20) {
                            title = title.substring(0, 20);
                        }
                        replyMap.put("title", title);
                        btnMap.put("reply", replyMap);
                        buttonsBody.add(btnMap);
                    }
                }
            }
            if (buttonsBody.isEmpty()) {
                log.warn("[MetaWhatsAppClient] Interactive button menu has 0 buttons for to={}. Falling back to plain text.", to);
                return sendMessage(to, bodyText, accessToken, phoneNumberId);
            }
            action.put("buttons", buttonsBody);
        } else {
            action.put("button",
                    menu.getButton() != null && !menu.getButton().isEmpty() ? menu.getButton() : "Options");
            List<Map<String, Object>> sectionsBody = new ArrayList<>();
            if (menu.getSections() != null) {
                for (MenuDto.MenuSectionDto sec : menu.getSections()) {
                    if (sec == null) continue;
                    Map<String, Object> sectionMap = new HashMap<>();
                    sectionMap.put("title", sec.getTitle() != null && !sec.getTitle().isBlank() ? sec.getTitle() : "Options");

                    List<Map<String, Object>> rowsBody = new ArrayList<>();
                    if (sec.getRows() != null) {
                        for (MenuDto.MenuRowDto row : sec.getRows()) {
                            if (row == null) continue;
                            Map<String, Object> rowMap = new HashMap<>();
                            rowMap.put("id", row.getId() != null ? row.getId() : "row_" + rowsBody.size());
                            rowMap.put("title", row.getTitle() != null && !row.getTitle().isBlank() ? row.getTitle() : "Option");
                            if (row.getDescription() != null && !row.getDescription().trim().isEmpty()) {
                                rowMap.put("description", row.getDescription());
                            }
                            rowsBody.add(rowMap);
                        }
                    }
                    if (!rowsBody.isEmpty()) {
                        sectionMap.put("rows", rowsBody);
                        sectionsBody.add(sectionMap);
                    }
                }
            }
            if (sectionsBody.isEmpty()) {
                log.warn("[MetaWhatsAppClient] Interactive list menu has 0 sections for to={}. Falling back to plain text.", to);
                return sendMessage(to, bodyText, accessToken, phoneNumberId);
            }
            action.put("sections", sectionsBody);
        }

        interactive.put("action", action);
        body.put("interactive", interactive);

        return executeApiCallWithRetry(url, headers, body);
    }

    private String executeApiCallWithRetry(String url, HttpHeaders headers, Map<String, Object> body) {
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        Retry retry = retryRegistry.retry("whatsAppClient");

        try {
            return Retry.decorateSupplier(retry, () -> {
                Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
                if (response != null && response.containsKey("messages")) {
                    Iterable<Map<String, Object>> messages = (Iterable<Map<String, Object>>) response.get("messages");
                    if (messages != null && messages.iterator().hasNext()) {
                        return (String) messages.iterator().next().get("id");
                    }
                }
                return "unknown_id";
            }).get();
        } catch (HttpStatusCodeException e) {
            String fullError = e.getResponseBodyAsString();
            log.error("❌ [MetaAPI] HTTP {} Error: {}", e.getStatusCode(), fullError);
            throw new RuntimeException("WhatsApp Error: " + parseMetaError(fullError, e.getStatusCode().toString()), e);
        } catch (Exception e) {
            log.error("❌ [MetaAPI] Error sending WhatsApp message: {}", e.getMessage());
            throw new RuntimeException("Failed to send WhatsApp message: " + e.getMessage(), e);
        }
    }

    private String parseMetaError(String errorResponse, String defaultCode) {
        if (errorResponse == null || errorResponse.isBlank()) {
            return "(" + defaultCode + ")";
        }
        try {
            JsonNode errorNode = objectMapper.readTree(errorResponse).path("error");
            String userMsg = errorNode.path("error_user_msg").asText("");
            String msg = !userMsg.isBlank() ? userMsg : errorNode.path("message").asText("");
            String code = errorNode.path("code").asText(defaultCode);
            return "(" + code + ") " + msg;
        } catch (Exception e) {
            return "(" + defaultCode + ") " + errorResponse;
        }
    }

    /**
     * POSTs a "status: read" back to Meta so the customer sees blue ticks (✓✓).
     * Endpoint: POST https://graph.facebook.com/v18.0/{phone-number-id}/messages
     * Body: { "messaging_product":"whatsapp", "status":"read",
     * "message_id":"<wamid>" }
     *
     * Failure is non-fatal — logged as a warning only, never throws.
     */
    @Override
    public void markAsRead(String waMessageId, String accessToken, String phoneNumberId) {
        if (waMessageId == null || waMessageId.isBlank())
            return;

        String url = String.format(META_URL, phoneNumberId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("status", "read");
        body.put("message_id", waMessageId);

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            restTemplate.postForObject(url, request, Map.class);
            log.debug("[MarkRead] Blue-tick sent for wamid={}", waMessageId);
        } catch (HttpStatusCodeException e) {
            log.warn("[MarkRead] Meta API rejected mark-as-read for wamid={}: {} {}",
                    waMessageId, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("[MarkRead] Failed to send mark-as-read for wamid={}: {}", waMessageId, e.getMessage());
        }
    }

    @Override
    public String sendLocation(String to, double latitude, double longitude, String name, String address,
            String accessToken, String phoneNumberId) {
        String url = String.format(META_URL, phoneNumberId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "location");

        Map<String, Object> location = new HashMap<>();
        location.put("latitude", latitude);
        location.put("longitude", longitude);
        location.put("name", name);
        location.put("address", address);
        body.put("location", location);

        return executeApiCallWithRetry(url, headers, body);
    }

    @Override
    public String sendSingleProductMessage(String to, String catalogId, String productRetailerId, String bodyText, String accessToken, String phoneNumberId) {
        String url = getGraphBaseUrl() + "/" + phoneNumberId + "/messages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", to);
        body.put("type", "interactive");

        Map<String, Object> interactive = new HashMap<>();
        interactive.put("type", "product");

        if (bodyText != null && !bodyText.isBlank()) {
            Map<String, Object> bodyObj = new HashMap<>();
            bodyObj.put("text", bodyText);
            interactive.put("body", bodyObj);
        }

        Map<String, Object> action = new HashMap<>();
        action.put("catalog_id", catalogId);
        action.put("product_retailer_id", productRetailerId);

        interactive.put("action", action);
        body.put("interactive", interactive);

        return executeApiCallWithRetry(url, headers, body);
    }

    @Override
    public String sendMultiProductMessage(String to, String catalogId, String headerText, String bodyText, String footerText, List<Map<String, Object>> sections, String accessToken, String phoneNumberId) {
        String url = getGraphBaseUrl() + "/" + phoneNumberId + "/messages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", to);
        body.put("type", "interactive");

        Map<String, Object> interactive = new HashMap<>();
        interactive.put("type", "product_list");

        if (headerText != null && !headerText.isBlank()) {
            Map<String, Object> headerObj = new HashMap<>();
            headerObj.put("type", "text");
            headerObj.put("text", headerText);
            interactive.put("header", headerObj);
        }

        Map<String, Object> bodyObj = new HashMap<>();
        bodyObj.put("text", bodyText != null && !bodyText.isBlank() ? bodyText : "Please view our products below:");
        interactive.put("body", bodyObj);

        if (footerText != null && !footerText.isBlank()) {
            Map<String, Object> footerObj = new HashMap<>();
            footerObj.put("text", footerText);
            interactive.put("footer", footerObj);
        }

        Map<String, Object> action = new HashMap<>();
        action.put("catalog_id", catalogId);
        action.put("sections", sections);

        interactive.put("action", action);
        body.put("interactive", interactive);

        return executeApiCallWithRetry(url, headers, body);
    }

    @Override
    public String sendCatalogTemplate(String to, String templateName, String languageCode, String accessToken, String phoneNumberId) {
        String url = String.format(META_URL, phoneNumberId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", to);
        body.put("type", "template");

        Map<String, Object> template = new HashMap<>();
        template.put("name", templateName);
        
        Map<String, String> lang = new HashMap<>();
        lang.put("code", languageCode != null ? languageCode : "en_US");
        template.put("language", lang);

        Map<String, Object> components = new HashMap<>();
        template.put("components", new ArrayList<>()); // Standard template message layout, Meta adds the CATALOG button natively based on template configuration.

        body.put("template", template);

        return executeApiCallWithRetry(url, headers, body);
    }

    /**
     * Fetch all HSM Message Templates from Meta Graph API for a WABA.
     * GET https://graph.facebook.com/v18.0/{wabaId}/message_templates
     */
    public JsonNode fetchMessageTemplates(String wabaId, String accessToken) {
        if (wabaId == null || wabaId.isBlank() || accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("WABA ID and Access Token must not be null or blank");
        }
        String url = String.format("%s/%s/message_templates?limit=100", getGraphBaseUrl(), wabaId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        try {
            HttpEntity<Void> request = new HttpEntity<>(headers);
            org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, request, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException e) {
            log.error("[MetaAPI] Failed to fetch message templates for WABA {}: {}", wabaId, e.getResponseBodyAsString());
            throw new RuntimeException("Meta API Error: " + parseMetaError(e.getResponseBodyAsString(), e.getStatusCode().toString()));
        } catch (Exception e) {
            log.error("[MetaAPI] Error fetching templates for WABA {}: {}", wabaId, e.getMessage());
            throw new RuntimeException("Failed to fetch templates from Meta Graph API");
        }
    }

    /**
     * Fetch a single message template by its Meta Template ID.
     * GET {graphBaseUrl}/{metaTemplateId}?fields=id,name,status,category,language,components
     */
    public JsonNode fetchSingleMessageTemplate(String metaTemplateId, String accessToken) {
        if (metaTemplateId == null || metaTemplateId.isBlank() || accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Meta Template ID and Access Token must not be null or blank");
        }
        String url = String.format("%s/%s?fields=id,name,status,category,language,components", getGraphBaseUrl(), metaTemplateId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        try {
            HttpEntity<Void> request = new HttpEntity<>(headers);
            org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, request, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException e) {
            log.error("[MetaAPI] Failed to fetch single message template {}: {}", metaTemplateId, e.getResponseBodyAsString());
            throw new RuntimeException("Meta API Error: " + parseMetaError(e.getResponseBodyAsString(), e.getStatusCode().toString()));
        } catch (Exception e) {
            log.error("[MetaAPI] Error fetching template {}: {}", metaTemplateId, e.getMessage());
            throw new RuntimeException("Failed to fetch template from Meta Graph API");
        }
    }

    /**
     * Create and submit a new HSM Message Template to Meta Graph API for review.
     * POST {graphBaseUrl}/{wabaId}/message_templates
     */
    public JsonNode createMessageTemplate(String wabaId, com.chatcrmlite.backend.dto.WhatsAppTemplateDto dto, String accessToken) {
        if (wabaId == null || wabaId.isBlank() || accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("WABA ID and Access Token must not be null or blank");
        }
        String url = String.format("%s/%s/message_templates", getGraphBaseUrl(), wabaId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", dto.getName().toLowerCase().replaceAll("[^a-z0-9_]", "_"));
        payload.put("language", dto.getLanguage() != null ? dto.getLanguage() : "en_US");
        payload.put("category", dto.getCategory() != null ? dto.getCategory() : "MARKETING");
        payload.put("components", buildComponentsList(dto));

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            return objectMapper.valueToTree(response);
        } catch (HttpStatusCodeException e) {
            log.error("[MetaAPI] Failed to create message template for WABA {}: {}", wabaId, e.getResponseBodyAsString());
            String metaError = parseMetaError(e.getResponseBodyAsString(), e.getStatusCode().toString());
            if (e.getStatusCode().is4xxClientError()) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Meta API Error: " + metaError);
            }
            throw new RuntimeException("Meta API Error: " + metaError);
        } catch (Exception e) {
            log.error("[MetaAPI] Error creating template: {}", e.getMessage());
            throw new RuntimeException("Failed to submit message template to Meta Graph API");
        }
    }

    /**
     * Update an existing HSM Message Template on Meta Graph API.
     * POST {graphBaseUrl}/{metaTemplateId}
     */
    public JsonNode updateMessageTemplate(String metaTemplateId, com.chatcrmlite.backend.dto.WhatsAppTemplateDto dto, String accessToken) {
        if (metaTemplateId == null || metaTemplateId.isBlank() || accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Meta Template ID and Access Token must not be null or blank");
        }
        String url = String.format("%s/%s", getGraphBaseUrl(), metaTemplateId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> payload = new HashMap<>();
        if (dto.getCategory() != null && !dto.getCategory().isBlank()) {
            payload.put("category", dto.getCategory());
        }
        payload.put("components", buildComponentsList(dto));

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            log.info("[MetaAPI] Successfully updated message template '{}' (metaId={})", dto.getName(), metaTemplateId);
            return objectMapper.valueToTree(response != null ? response : Map.of("success", true));
        } catch (HttpStatusCodeException e) {
            log.error("[MetaAPI] Failed to update message template {} (metaId={}): {}", dto.getName(), metaTemplateId, e.getResponseBodyAsString());
            String metaError = parseMetaError(e.getResponseBodyAsString(), e.getStatusCode().toString());
            if (e.getStatusCode().is4xxClientError()) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Meta API Error: " + metaError);
            }
            throw new RuntimeException("Meta API Error: " + metaError);
        } catch (Exception e) {
            log.error("[MetaAPI] Error updating template {}: {}", dto.getName(), e.getMessage());
            throw new RuntimeException("Failed to update message template on Meta Graph API: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> buildComponentsList(com.chatcrmlite.backend.dto.WhatsAppTemplateDto dto) {
        List<Map<String, Object>> components = new ArrayList<>();

        // 1. Header Component
        if (dto.getHeaderType() != null && !"NONE".equalsIgnoreCase(dto.getHeaderType())) {
            Map<String, Object> header = new HashMap<>();
            header.put("type", "HEADER");
            header.put("format", dto.getHeaderType().toUpperCase());
            if ("TEXT".equalsIgnoreCase(dto.getHeaderType()) && dto.getHeaderContent() != null) {
                header.put("text", dto.getHeaderContent());
                Map<String, Object> headerExample = buildHeaderExample(dto.getHeaderContent(), dto.getHeaderSampleValues());
                if (headerExample != null) {
                    header.put("example", headerExample);
                    log.info("[MetaAPI] Attached header example for template '{}'", dto.getName());
                }
            }
            components.add(header);
        }

        // 2. Body Component
        Map<String, Object> bodyComponent = new HashMap<>();
        bodyComponent.put("type", "BODY");
        bodyComponent.put("text", dto.getBodyText());

        Map<String, Object> bodyExample = buildBodyExample(dto.getBodyText(), dto.getBodySampleValues());
        if (bodyExample != null) {
            bodyComponent.put("example", bodyExample);
            log.info("[MetaAPI] Attached body example for template '{}'", dto.getName());
        }
        components.add(bodyComponent);

        // 3. Footer Component
        if (dto.getFooterText() != null && !dto.getFooterText().isBlank()) {
            Map<String, Object> footer = new HashMap<>();
            footer.put("type", "FOOTER");
            footer.put("text", dto.getFooterText());
            components.add(footer);
        }

        // 4. Buttons Component
        if (dto.getButtons() != null && !dto.getButtons().isEmpty()) {
            Map<String, Object> buttonsComponent = new HashMap<>();
            buttonsComponent.put("type", "BUTTONS");
            List<Map<String, Object>> buttonsList = new ArrayList<>();
            for (com.chatcrmlite.backend.dto.WhatsAppTemplateDto.TemplateButtonDto btn : dto.getButtons()) {
                if (btn == null || btn.getText() == null || btn.getText().isBlank()) continue;
                Map<String, Object> btnMap = new HashMap<>();
                btnMap.put("type", btn.getType());
                btnMap.put("text", btn.getText().trim());
                if ("URL".equalsIgnoreCase(btn.getType())) {
                    String urlVal = (btn.getUrl() != null && !btn.getUrl().isBlank()) ? btn.getUrl().trim() : "https://example.com";
                    btnMap.put("url", urlVal);
                    List<String> buttonExample = buildButtonExample(btn);
                    if (buttonExample != null && !buttonExample.isEmpty()) {
                        btnMap.put("example", buttonExample);
                        log.info("[MetaAPI] Attached dynamic URL example for button '{}'", btn.getText());
                    }
                } else if ("PHONE_NUMBER".equalsIgnoreCase(btn.getType())) {
                    String phoneVal = (btn.getPhoneNumber() != null && !btn.getPhoneNumber().isBlank()) ? btn.getPhoneNumber().trim() : "+919876543210";
                    btnMap.put("phone_number", phoneVal);
                } else if ("FLOW".equalsIgnoreCase(btn.getType())) {
                    btnMap.put("flow_id", btn.getFlowId());
                    btnMap.put("flow_action", (btn.getFlowAction() != null && !btn.getFlowAction().isBlank()) ? btn.getFlowAction().toLowerCase() : "navigate");
                    if (btn.getNavigateScreen() != null && !btn.getNavigateScreen().isBlank()) {
                        btnMap.put("navigate_screen", btn.getNavigateScreen().trim());
                    }
                }
                buttonsList.add(btnMap);
            }
            if (!buttonsList.isEmpty()) {
                buttonsComponent.put("buttons", buttonsList);
                components.add(buttonsComponent);
            }
        }

        return components;
    }

    /**
     * Delete an HSM Message Template from Meta Graph API.
     * DELETE {graphBaseUrl}/{wabaId}/message_templates?name={templateName}
     */
    public void deleteMessageTemplate(String wabaId, String templateName, String accessToken) {
        String url = String.format("%s/%s/message_templates?name=%s", getGraphBaseUrl(), wabaId, templateName);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        try {
            HttpEntity<Void> request = new HttpEntity<>(headers);
            restTemplate.exchange(url, org.springframework.http.HttpMethod.DELETE, request, Void.class);
            log.info("[MetaAPI] Deleted message template '{}' for WABA {}", templateName, wabaId);
        } catch (HttpStatusCodeException e) {
            log.error("[MetaAPI] Failed to delete message template '{}': {}", templateName, e.getResponseBodyAsString());
            String metaError = parseMetaError(e.getResponseBodyAsString(), e.getStatusCode().toString());
            if (e.getStatusCode().is4xxClientError()) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Meta API Error: " + metaError);
            }
            throw new RuntimeException("Meta API Error: " + metaError);
        }
    }

    @Override
    public String sendFlowMessage(String to, String headerText, String bodyText, String footerText,
                                  String metaFlowId, String ctaText, String flowToken, String screen,
                                  String accessToken, String phoneNumberId) {
        String url = String.format(META_URL, phoneNumberId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", to);
        body.put("type", "interactive");

        Map<String, Object> interactive = new HashMap<>();
        interactive.put("type", "flow");

        if (headerText != null && !headerText.isBlank()) {
            Map<String, String> header = new HashMap<>();
            header.put("type", "text");
            header.put("text", headerText);
            interactive.put("header", header);
        }

        Map<String, String> bodyMap = new HashMap<>();
        bodyMap.put("text", (bodyText != null && !bodyText.isBlank()) ? bodyText : "Please complete the form below:");
        interactive.put("body", bodyMap);

        if (footerText != null && !footerText.isBlank()) {
            Map<String, String> footer = new HashMap<>();
            footer.put("text", footerText);
            interactive.put("footer", footer);
        }

        Map<String, Object> action = new HashMap<>();
        action.put("name", "flow");

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("flow_message_version", "3");
        parameters.put("flow_token", (flowToken != null && !flowToken.isBlank()) ? flowToken : "flow_token_" + System.currentTimeMillis());
        parameters.put("flow_id", metaFlowId);
        parameters.put("flow_cta", (ctaText != null && !ctaText.isBlank()) ? ctaText : "Open Form");
        parameters.put("flow_action", "navigate");

        Map<String, Object> flowActionPayload = new HashMap<>();
        flowActionPayload.put("screen", (screen != null && !screen.isBlank()) ? screen : "MAIN_SCREEN");
        parameters.put("flow_action_payload", flowActionPayload);

        action.put("parameters", parameters);
        interactive.put("action", action);
        body.put("interactive", interactive);

        return executeApiCallWithRetry(url, headers, body);
    }

    @Override
    public com.chatcrmlite.backend.dto.MetaMediaDto fetchMediaMetadata(String mediaId, String accessToken) {
        if (mediaId == null || mediaId.isBlank() || accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("mediaId and accessToken must not be null or blank");
        }

        String url = String.format("https://graph.facebook.com/v18.0/%s", mediaId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        Retry retry = retryRegistry.retry("whatsAppClient");

        try {
            return Retry.decorateSupplier(retry, () -> {
                HttpEntity<Void> request = new HttpEntity<>(headers);
                org.springframework.http.ResponseEntity<com.chatcrmlite.backend.dto.MetaMediaDto> response =
                        restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, request, com.chatcrmlite.backend.dto.MetaMediaDto.class);
                return response.getBody();
            }).get();
        } catch (HttpStatusCodeException e) {
            String fullError = e.getResponseBodyAsString();
            log.error("❌ [MetaAPI] Failed to fetch media metadata for mediaId {}: HTTP {} - {}", mediaId, e.getStatusCode(), fullError);
            throw new RuntimeException("Meta Media Metadata Error: " + parseMetaError(fullError, e.getStatusCode().toString()), e);
        } catch (Exception e) {
            log.error("❌ [MetaAPI] Error fetching media metadata for mediaId {}: {}", mediaId, e.getMessage());
            throw new RuntimeException("Failed to fetch WhatsApp media metadata: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T streamMedia(String mediaUrl, String accessToken, long maxSizeBytes, MediaStreamConsumer<T> consumer) {
        if (mediaUrl == null || mediaUrl.isBlank() || accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("mediaUrl and accessToken must not be null or blank");
        }
        if (consumer == null) {
            throw new IllegalArgumentException("MediaStreamConsumer must not be null");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set(HttpHeaders.USER_AGENT, "Meta-WhatsApp-Client/1.0");

        Retry retry = retryRegistry.retry("whatsAppClient");

        try {
            return Retry.decorateSupplier(retry, () -> {
                return restTemplate.execute(mediaUrl, org.springframework.http.HttpMethod.GET, request -> {
                    request.getHeaders().addAll(headers);
                }, response -> {
                    if (response.getStatusCode().isError()) {
                        throw new org.springframework.web.client.HttpClientErrorException(
                                response.getStatusCode(),
                                "Meta media download failed with status " + response.getStatusCode()
                        );
                    }
                    try (java.io.InputStream rawIn = response.getBody();
                         com.chatcrmlite.backend.utils.BoundedCountingInputStream boundedIn =
                                 new com.chatcrmlite.backend.utils.BoundedCountingInputStream(rawIn, maxSizeBytes)) {
                        return consumer.consume(boundedIn);
                    } catch (Exception e) {
                        if (e instanceof RuntimeException re) throw re;
                        throw new RuntimeException(e);
                    }
                });
            }).get();
        } catch (HttpStatusCodeException e) {
            String fullError = e.getResponseBodyAsString();
            log.error("❌ [MetaAPI] Failed to stream media from {}: HTTP {} - {}", mediaUrl, e.getStatusCode(), fullError);
            throw new RuntimeException("Meta Media Stream Error: " + parseMetaError(fullError, e.getStatusCode().toString()), e);
        } catch (Exception e) {
            log.error("❌ [MetaAPI] Error streaming media from {}: {}", mediaUrl, e.getMessage());
            throw new RuntimeException("Failed to stream WhatsApp media: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] downloadMedia(String mediaUrl, String accessToken) {
        return streamMedia(mediaUrl, accessToken, 100L * 1024 * 1024, in -> in.readAllBytes());
    }

    // ==========================================
    // WHATSAPP GRAPH CALLING ENDPOINTS (v21.0+)
    // ==========================================

    public JsonNode preAcceptCall(String phoneNumberId, String accessToken, String callId, String sdpAnswer) {
        String url = String.format("%s/%s/%s/calls", apiBaseUrl, apiVersion, phoneNumberId);
        Map<String, Object> session = Map.of(
            "sdp_type", "answer",
            "sdp", sdpAnswer != null ? sdpAnswer : ""
        );
        Map<String, Object> body = Map.of(
            "action", "pre_accept",
            "call_id", callId,
            "session", session
        );
        return executePostCall(url, accessToken, body, "pre_accept");
    }

    public JsonNode acceptIncomingCall(String phoneNumberId, String accessToken, String callId) {
        String url = String.format("%s/%s/%s/calls", apiBaseUrl, apiVersion, phoneNumberId);
        Map<String, Object> body = Map.of(
            "action", "accept",
            "call_id", callId
        );
        return executePostCall(url, accessToken, body, "accept");
    }

    public JsonNode rejectIncomingCall(String phoneNumberId, String accessToken, String callId, String reason) {
        String url = String.format("%s/%s/%s/calls", apiBaseUrl, apiVersion, phoneNumberId);
        Map<String, Object> body = Map.of(
            "action", "reject",
            "call_id", callId,
            "reason", reason != null ? reason : "USER_BUSY"
        );
        return executePostCall(url, accessToken, body, "reject");
    }

    public JsonNode initiateBusinessCall(String phoneNumberId, String accessToken, String toWaId, String sdpOffer, String bizOpaqueCallbackData) {
        String url = String.format("%s/%s/%s/calls", apiBaseUrl, apiVersion, phoneNumberId);
        Map<String, Object> session = Map.of(
            "sdp_type", "offer",
            "sdp", sdpOffer != null ? sdpOffer : ""
        );
        Map<String, Object> body = new HashMap<>();
        body.put("action", "connect");
        body.put("to", toWaId);
        body.put("session", session);
        if (bizOpaqueCallbackData != null && !bizOpaqueCallbackData.isBlank()) {
            body.put("biz_opaque_callback_data", bizOpaqueCallbackData);
        }
        return executePostCall(url, accessToken, body, "connect");
    }

    public JsonNode terminateCall(String phoneNumberId, String accessToken, String callId) {
        String url = String.format("%s/%s/%s/calls", apiBaseUrl, apiVersion, phoneNumberId);
        Map<String, Object> body = Map.of(
            "action", "terminate",
            "call_id", callId
        );
        return executePostCall(url, accessToken, body, "terminate");
    }

    private JsonNode executePostCall(String url, String accessToken, Map<String, Object> body, String actionName) {
        Map<String, Object> payload = new HashMap<>(body);
        payload.putIfAbsent("messaging_product", "whatsapp");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        Retry retry = retryRegistry.retry("whatsAppClient");

        try {
            return Retry.decorateSupplier(retry, () -> {
                return restTemplate.postForObject(url, entity, JsonNode.class);
            }).get();
        } catch (HttpStatusCodeException e) {
            String fullError = e.getResponseBodyAsString();
            log.error("❌ [MetaAPI] Call action '{}' failed: HTTP {} - {}", actionName, e.getStatusCode(), fullError);
            throw new RuntimeException("Meta Call API Error (" + actionName + "): " + parseMetaError(fullError, e.getStatusCode().toString()), e);
        } catch (Exception e) {
            log.error("❌ [MetaAPI] Error executing call action '{}': {}", actionName, e.getMessage());
            throw new RuntimeException("Failed to execute Meta call action (" + actionName + "): " + e.getMessage(), e);
        }
    }

    public JsonNode getCallPermissions(String phoneNumberId, String accessToken, String userWaId) {
        String url = String.format("%s/%s/%s/call_permissions?user=%s", apiBaseUrl, apiVersion, phoneNumberId, userWaId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        Retry retry = retryRegistry.retry("whatsAppClient");

        try {
            return Retry.decorateSupplier(retry, () -> {
                return restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, JsonNode.class).getBody();
            }).get();
        } catch (Exception e) {
            log.warn("⚠️ [MetaAPI] Failed to fetch call permissions for userWaId={}: {}", userWaId, e.getMessage());
            return null;
        }
    }

    public JsonNode sendCallPermissionRequest(String phoneNumberId, String accessToken, String toWaId, String messageText) {
        String url = String.format("%s/%s/%s/messages", apiBaseUrl, apiVersion, phoneNumberId);
        Map<String, Object> body = Map.of(
            "messaging_product", "whatsapp",
            "recipient_type", "individual",
            "to", toWaId,
            "type", "interactive",
            "interactive", Map.of(
                "type", "voice_call",
                "body", Map.of("text", messageText != null ? messageText : "Would you like our AI assistant to call you?"),
                "action", Map.of("name", "call_permission_request")
            )
        );
        return executePostCall(url, accessToken, body, "send_call_permission_request");
    }
}

