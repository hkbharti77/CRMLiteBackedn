package com.chatcrmlite.backend.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MetaFlowClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${meta.api-base-url:https://graph.facebook.com}")
    private String apiBaseUrl;

    @Value("${meta.graph-api-version:v21.0}")
    private String apiVersion;

    public MetaFlowClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    private String buildUrl(String path) {
        String base = (apiBaseUrl != null && !apiBaseUrl.isBlank()) ? apiBaseUrl.trim() : "https://graph.facebook.com";
        String ver = (apiVersion != null && !apiVersion.isBlank()) ? apiVersion.trim() : "v21.0";
        if (!base.endsWith("/")) base += "/";
        if (ver.startsWith("/")) ver = ver.substring(1);
        if (!ver.endsWith("/")) ver += "/";
        if (path.startsWith("/")) path = path.substring(1);
        return base + ver + path;
    }

    /**
     * Creates a Meta Flow container on the WABA.
     */
    public String createFlowContainer(String wabaId, String name, List<String> categories, String accessToken) {
        String url = buildUrl(wabaId + "/flows");
        log.info("🚀 [MetaFlowClient] Creating Flow Container for WABA {}: name='{}', categories={}", wabaId, name, categories);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("categories", categories);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            String flowId = root.path("id").asText();
            if (flowId == null || flowId.isBlank()) {
                throw new IllegalStateException("Meta API returned empty Flow ID: " + response.getBody());
            }
            log.info("✅ [MetaFlowClient] Created Meta Flow Container ID: {}", flowId);
            return flowId;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            try {
                JsonNode errNode = objectMapper.readTree(e.getResponseBodyAsString()).path("error");
                if (errNode.path("error_subcode").asInt() == 4016019 ||
                    errNode.path("error_user_title").asText("").toLowerCase().contains("flow name is not unique") ||
                    errNode.path("message").asText("").toLowerCase().contains("flow name is not unique")) {
                    log.info("ℹ️ [MetaFlowClient] Flow name '{}' already registered on Meta WABA {}. Resolving existing Flow ID...", name, wabaId);
                    String existingFlowId = findExistingFlowIdByName(wabaId, name, accessToken);
                    if (existingFlowId != null && !existingFlowId.isBlank()) {
                        log.info("🔗 [MetaFlowClient] Reusing existing Meta Flow ID: {}", existingFlowId);
                        return existingFlowId;
                    }
                }
            } catch (Exception ignored) {}

            log.error("❌ [MetaFlowClient] Failed to create flow container: {}", e.getResponseBodyAsString(), e);
            throw new RuntimeException("Meta API Error: " + extractErrorMessage(e));
        } catch (Exception e) {
            log.error("❌ [MetaFlowClient] Unexpected error creating flow container: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Searches for an existing Meta Flow ID by name within a WABA.
     */
    public String findExistingFlowIdByName(String wabaId, String name, String accessToken) {
        String url = buildUrl(wabaId + "/flows?fields=id,name,status");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode item : data) {
                    if (name.equalsIgnoreCase(item.path("name").asText("").trim())) {
                        return item.path("id").asText();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [MetaFlowClient] Failed to query existing flows for WABA {}: {}", wabaId, e.getMessage());
        }
        return null;
    }

    /**
     * Uploads the flow.json asset to Meta for validation and compilation.
     */
    public void uploadFlowAssets(String metaFlowId, String flowJson, String accessToken) {
        String url = buildUrl(metaFlowId + "/assets");
        log.info("📤 [MetaFlowClient] Uploading flow.json asset to Meta Flow ID: {}", metaFlowId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(accessToken);

        ByteArrayResource fileResource = new ByteArrayResource(flowJson.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "flow.json";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("name", "flow.json");
        body.add("asset_type", "FLOW_JSON");
        body.add("file", fileResource);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            boolean success = root.path("success").asBoolean(true);
            if (!success) {
                throw new IllegalStateException("Asset upload unconfirmed: " + response.getBody());
            }
            log.info("✅ [MetaFlowClient] Uploaded flow.json asset for Meta Flow ID: {}", metaFlowId);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("❌ [MetaFlowClient] Failed to upload assets to flow {}: {}", metaFlowId, e.getResponseBodyAsString(), e);
            throw new RuntimeException("Asset Upload Error: " + extractErrorMessage(e));
        } catch (Exception e) {
            log.error("❌ [MetaFlowClient] Unexpected error uploading assets: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Publishes a Meta Flow so customers can interact with it on WhatsApp.
     */
    public void publishFlow(String metaFlowId, String accessToken) {
        String url = buildUrl(metaFlowId + "/publish");
        log.info("📢 [MetaFlowClient] Publishing Meta Flow ID: {}", metaFlowId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        HttpEntity<String> request = new HttpEntity<>("{}", headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            boolean success = root.path("success").asBoolean(true);
            if (!success) {
                throw new IllegalStateException("Publish unconfirmed: " + response.getBody());
            }
            log.info("✅ [MetaFlowClient] Published Meta Flow ID: {}", metaFlowId);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("❌ [MetaFlowClient] Failed to publish flow {}: {}", metaFlowId, e.getResponseBodyAsString(), e);
            throw new RuntimeException("Publish Error: " + extractErrorMessage(e));
        } catch (Exception e) {
            log.error("❌ [MetaFlowClient] Unexpected error publishing flow: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Retrieves flow status and validation errors from Meta.
     */
    public JsonNode getFlowDetails(String metaFlowId, String accessToken) {
        String url = buildUrl(metaFlowId + "?fields=id,name,status,categories,validation_errors");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.warn("⚠️ [MetaFlowClient] Could not fetch details for flow {}: {}", metaFlowId, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches all flows registered under a WABA from Meta Graph API.
     */
    public JsonNode fetchWabaFlows(String wabaId, String accessToken) {
        String url = buildUrl(wabaId + "/flows?fields=id,name,status,categories,validation_errors&limit=100");
        log.info("📥 [MetaFlowClient] Fetching all Flows from Meta for WABA {}", wabaId);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.error("❌ [MetaFlowClient] Failed to fetch flows for WABA {}: {}", wabaId, e.getMessage());
            throw new RuntimeException("Failed to fetch flows from Meta: " + extractErrorMessage(e));
        }
    }

    /**
     * Deprecates / deletes flow from Meta Graph API.
     */
    public void deleteFlow(String metaFlowId, String accessToken) {
        String url = buildUrl(metaFlowId);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            restTemplate.exchange(url, HttpMethod.DELETE, request, String.class);
            log.info("🗑️ [MetaFlowClient] Deleted flow on Meta: {}", metaFlowId);
        } catch (Exception deleteEx) {
            log.warn("⚠️ [MetaFlowClient] Direct DELETE failed for flow {} (might be published). Attempting deprecation...", metaFlowId);
            try {
                // For published flows, Meta requires deprecation instead of hard delete
                String deprecateUrl = buildUrl(metaFlowId + "/deprecation");
                HttpHeaders postHeaders = new HttpHeaders();
                postHeaders.setContentType(MediaType.APPLICATION_JSON);
                postHeaders.setBearerAuth(accessToken);
                HttpEntity<String> postRequest = new HttpEntity<>("{}", postHeaders);
                restTemplate.postForEntity(deprecateUrl, postRequest, String.class);
                log.info("🔒 [MetaFlowClient] Deprecated published flow on Meta: {}", metaFlowId);
            } catch (Exception depEx) {
                log.warn("⚠️ [MetaFlowClient] Could not deprecate flow {} on Meta: {}", metaFlowId, depEx.getMessage());
            }
        }
    }

    private String extractErrorMessage(Exception e) {
        if (e instanceof HttpClientErrorException hce) {
            try {
                JsonNode root = objectMapper.readTree(hce.getResponseBodyAsString());
                if (root.has("error")) {
                    return root.path("error").path("message").asText();
                }
            } catch (Exception ignored) {}
            return hce.getResponseBodyAsString();
        }
        return e.getMessage();
    }
}
