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

    private static final String META_URL = "https://graph.facebook.com/v18.0/%s/messages"; // Slightly more standard
                                                                                           // version

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

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);

            if (response != null && response.containsKey("messages")) {
                Iterable<Map<String, Object>> messages = (Iterable<Map<String, Object>>) response.get("messages");
                return (String) messages.iterator().next().get("id");
            }
            return "unknown_id";
        } catch (HttpStatusCodeException e) {
            String errorResponse = e.getResponseBodyAsString();
            String errorMessage = "WhatsApp API Error: " + e.getStatusCode();
            try {
                JsonNode errorNode = objectMapper.readTree(errorResponse);
                errorMessage = errorNode.path("error").path("message").asText();
                String errorCode = errorNode.path("error").path("code").asText();
                errorMessage = "WhatsApp Error (" + errorCode + "): " + errorMessage;
            } catch (Exception ignored) {
            }

            System.err.println("Meta API Failure: " + errorMessage);
            throw new RuntimeException(errorMessage);
        } catch (Exception e) {
            System.err.println("General Error sending WhatsApp: " + e.getMessage());
            throw new RuntimeException("Connection error or timeout while sending WhatsApp");
        }
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

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            if (response != null && response.containsKey("messages")) {
                Iterable<Map<String, Object>> messages = (Iterable<Map<String, Object>>) response.get("messages");
                return (String) messages.iterator().next().get("id");
            }
            return "unknown_id";
        } catch (HttpStatusCodeException e) {
            String errorResponse = e.getResponseBodyAsString();
            System.err.println("Meta API Failure (Image): " + errorResponse);
            throw new RuntimeException("Failed to send image: " + e.getStatusCode());
        } catch (Exception e) {
            System.err.println("General Error sending image: " + e.getMessage());
            throw new RuntimeException("Connection error sending image");
        }
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
                        Map<String, Object> btnMap = new HashMap<>();
                        btnMap.put("type", "reply");
                        Map<String, Object> replyMap = new HashMap<>();
                        replyMap.put("id", row.getId());
                        // WhatsApp button title: max 20 chars
                        String title = row.getTitle();
                        if (title != null && title.length() > 20) {
                            title = title.substring(0, 20);
                        }
                        replyMap.put("title", title);
                        btnMap.put("reply", replyMap);
                        buttonsBody.add(btnMap);
                    }
                }
            }
            action.put("buttons", buttonsBody);
        } else {
            action.put("button",
                    menu.getButton() != null && !menu.getButton().isEmpty() ? menu.getButton() : "Options");
            List<Map<String, Object>> sectionsBody = new ArrayList<>();
            if (menu.getSections() != null) {
                for (MenuDto.MenuSectionDto sec : menu.getSections()) {
                    Map<String, Object> sectionMap = new HashMap<>();
                    sectionMap.put("title", sec.getTitle());

                    List<Map<String, Object>> rowsBody = new ArrayList<>();
                    if (sec.getRows() != null) {
                        for (MenuDto.MenuRowDto row : sec.getRows()) {
                            Map<String, Object> rowMap = new HashMap<>();
                            rowMap.put("id", row.getId());
                            rowMap.put("title", row.getTitle());
                            if (row.getDescription() != null && !row.getDescription().trim().isEmpty()) {
                                rowMap.put("description", row.getDescription());
                            }
                            rowsBody.add(rowMap);
                        }
                    }
                    sectionMap.put("rows", rowsBody);
                    sectionsBody.add(sectionMap);
                }
            }
            action.put("sections", sectionsBody);
        }

        interactive.put("action", action);
        body.put("interactive", interactive);

        return executeApiCallWithRetry(url, headers, body);
    }

    private String executeApiCallWithRetry(String url, HttpHeaders headers, Map<String, Object> body) {
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        try {
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            if (response != null && response.containsKey("messages")) {
                Iterable<Map<String, Object>> messages = (Iterable<Map<String, Object>>) response.get("messages");
                return (String) messages.iterator().next().get("id");
            }
            return "unknown_id";
        } catch (HttpStatusCodeException e) {
            String fullError = e.getResponseBodyAsString();
            System.err.println("\u274C Meta API Error: " + fullError);
            log.error("[MetaAPI] 4xx/5xx Error: {}", fullError);
            System.err.println("Meta API Error: " + fullError + ". Retrying once...");
            try {
                Thread.sleep(500); // Wait half a second before retry
                Map<String, Object> retryResponse = restTemplate.postForObject(url, request, Map.class);
                if (retryResponse != null && retryResponse.containsKey("messages")) {
                    Iterable<Map<String, Object>> m = (Iterable<Map<String, Object>>) retryResponse.get("messages");
                    return (String) m.iterator().next().get("id");
                }
                return "unknown_id";
            } catch (Exception retryEx) {
                System.err.println("Retry failed: " + retryEx.getMessage());
                throw new RuntimeException(
                        "WhatsApp Error: " + parseMetaError(e.getResponseBodyAsString(), e.getStatusCode().toString()));
            }
        } catch (Exception e) {
            System.err.println("General Error sending interactive WhatsApp: " + e.getMessage());
            throw new RuntimeException("Failed to send interactive message");
        }
    }

    private String parseMetaError(String errorResponse, String defaultCode) {
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
                List<String> sampleValues = new ArrayList<>();
                for (Integer idx : varIndices) {
                    sampleValues.add("SampleValue" + idx);
                }
                Map<String, Object> exampleObj = new HashMap<>();
                exampleObj.put("body_text", List.of(sampleValues));
                bodyComponent.put("example", exampleObj);
                log.info("[MetaAPI] Added variable example.body_text for {} variables: {}", varIndices.size(), sampleValues);
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
            throw new RuntimeException("Meta API Error: " + parseMetaError(e.getResponseBodyAsString(), e.getStatusCode().toString()));
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
            throw new RuntimeException("Meta API Error: " + parseMetaError(e.getResponseBodyAsString(), e.getStatusCode().toString()));
        }
    }
}
