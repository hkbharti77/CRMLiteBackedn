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

        // Header and Body text
        if (menu.getHeaderImageUrl() != null && !menu.getHeaderImageUrl().isBlank()) {
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
    public String sendCatalogMessage(String to, String text, String accessToken, String phoneNumberId) {
        String url = String.format(META_URL, phoneNumberId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "interactive");

        Map<String, Object> interactive = new HashMap<>();
        interactive.put("type", "catalog_message");

        Map<String, Object> bodyObj = new HashMap<>();
        bodyObj.put("text", text != null && !text.isBlank() ? text : "Please check out our catalog below:");
        interactive.put("body", bodyObj);

        Map<String, Object> action = new HashMap<>();
        action.put("name", "catalog_message");

        // We omit parameters (thumbnail_product_retailer_id) to use default
        interactive.put("action", action);
        body.put("interactive", interactive);

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
        String url = String.format("https://graph.facebook.com/v18.0/%s/message_templates?limit=100", wabaId);
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
     * Create and submit a new HSM Message Template to Meta Graph API for review.
     * POST https://graph.facebook.com/v18.0/{wabaId}/message_templates
     */
    public JsonNode createMessageTemplate(String wabaId, com.chatcrmlite.backend.dto.WhatsAppTemplateDto dto, String accessToken) {
        if (wabaId == null || wabaId.isBlank() || accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("WABA ID and Access Token must not be null or blank");
        }
        String url = String.format("https://graph.facebook.com/v18.0/%s/message_templates", wabaId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", dto.getName().toLowerCase().replaceAll("[^a-z0-9_]", "_"));
        payload.put("language", dto.getLanguage() != null ? dto.getLanguage() : "en_US");
        payload.put("category", dto.getCategory() != null ? dto.getCategory() : "MARKETING");

        List<Map<String, Object>> components = new ArrayList<>();

        // 1. Header Component
        if (dto.getHeaderType() != null && !"NONE".equalsIgnoreCase(dto.getHeaderType())) {
            Map<String, Object> header = new HashMap<>();
            header.put("type", "HEADER");
            header.put("format", dto.getHeaderType().toUpperCase());
            if ("TEXT".equalsIgnoreCase(dto.getHeaderType()) && dto.getHeaderContent() != null) {
                header.put("text", dto.getHeaderContent());
            }
            components.add(header);
        }

        // 2. Body Component
        Map<String, Object> bodyComponent = new HashMap<>();
        bodyComponent.put("type", "BODY");
        bodyComponent.put("text", dto.getBodyText());

        // Extract variables {{1}}, {{2}}, ... and supply required example.body_text for Meta API
        if (dto.getBodyText() != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\\{(\\d+)\\}\\}").matcher(dto.getBodyText());
            java.util.Set<Integer> varIndices = new java.util.TreeSet<>();
            while (matcher.find()) {
                try {
                    varIndices.add(Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {}
            }
            if (!varIndices.isEmpty()) {
                int maxIndex = varIndices.stream().max(Integer::compareTo).get();
                List<String> sampleValues = new ArrayList<>();
                for (int i = 1; i <= maxIndex; i++) {
                    sampleValues.add("SampleValue" + i);
                }
                Map<String, Object> exampleObj = new HashMap<>();
                exampleObj.put("body_text", List.of(sampleValues));
                bodyComponent.put("example", exampleObj);
                log.info("[MetaAPI] Added variable example.body_text for {} max variables: {}", maxIndex, sampleValues);
            }
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
                Map<String, Object> btnMap = new HashMap<>();
                btnMap.put("type", btn.getType());
                btnMap.put("text", btn.getText());
                if ("URL".equalsIgnoreCase(btn.getType()) && btn.getUrl() != null) {
                    btnMap.put("url", btn.getUrl());
                } else if ("PHONE_NUMBER".equalsIgnoreCase(btn.getType()) && btn.getPhoneNumber() != null) {
                    btnMap.put("phone_number", btn.getPhoneNumber());
                }
                buttonsList.add(btnMap);
            }
            buttonsComponent.put("buttons", buttonsList);
            components.add(buttonsComponent);
        }

        payload.put("components", components);

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
     * Delete an HSM Message Template from Meta Graph API.
     * DELETE https://graph.facebook.com/v18.0/{wabaId}/message_templates?name={templateName}
     */
    public void deleteMessageTemplate(String wabaId, String templateName, String accessToken) {
        String url = String.format("https://graph.facebook.com/v18.0/%s/message_templates?name=%s", wabaId, templateName);
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
}
