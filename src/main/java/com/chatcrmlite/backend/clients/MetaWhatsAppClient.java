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

    private static final String META_URL = "https://graph.facebook.com/v18.0/%s/messages"; // Slightly more standard version

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
            } catch (Exception ignored) {}
            
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
            action.put("button", menu.getButton() != null && !menu.getButton().isEmpty() ? menu.getButton() : "Options");
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
                throw new RuntimeException("WhatsApp Error: " + parseMetaError(e.getResponseBodyAsString(), e.getStatusCode().toString()));
            }
        } catch (Exception e) {
            System.err.println("General Error sending interactive WhatsApp: " + e.getMessage());
            throw new RuntimeException("Failed to send interactive message");
        }
    }

    private String parseMetaError(String errorResponse, String defaultCode) {
        try {
            JsonNode errorNode = objectMapper.readTree(errorResponse);
            String errorMsg = errorNode.path("error").path("message").asText();
            String errorCode = errorNode.path("error").path("code").asText();
            return "(" + errorCode + ") " + errorMsg;
        } catch (Exception e) {
            return "(" + defaultCode + ") " + errorResponse;
        }
    }

    /**
     * POSTs a "status: read" back to Meta so the customer sees blue ticks (✓✓).
     * Endpoint: POST https://graph.facebook.com/v18.0/{phone-number-id}/messages
     * Body: { "messaging_product":"whatsapp", "status":"read", "message_id":"<wamid>" }
     *
     * Failure is non-fatal — logged as a warning only, never throws.
     */
    @Override
    public void markAsRead(String waMessageId, String accessToken, String phoneNumberId) {
        if (waMessageId == null || waMessageId.isBlank()) return;

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
    public String sendLocation(String to, double latitude, double longitude, String name, String address, String accessToken, String phoneNumberId) {
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
}

