package com.chatcrmlite.backend.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class MetaCommerceClient {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${meta.api-base-url:https://graph.facebook.com}")
    private String apiBaseUrl;

    @Value("${meta.graph-api-version:v21.0}")
    private String apiVersion;

    private String getGraphBaseUrl() {
        String base = (apiBaseUrl != null && !apiBaseUrl.isBlank()) ? apiBaseUrl.replaceAll("/+$", "") : "https://graph.facebook.com";
        String ver = (apiVersion != null && !apiVersion.isBlank()) ? apiVersion.trim() : "v21.0";
        if (!ver.startsWith("v")) ver = "v" + ver;
        return base + "/" + ver;
    }

    /**
     * Resolves the true Meta Business Manager / Portfolio ID from the WABA owner info or /me/businesses.
     */
    public String resolveBusinessManagerId(String wabaId, String accessToken) {
        if (wabaId != null && !wabaId.isBlank()) {
            try {
                String url = String.format("%s/%s?fields=owner_business_info&access_token=%s", getGraphBaseUrl(), wabaId, accessToken);
                JsonNode root = executeGet(url, accessToken);
                if (root != null && root.has("owner_business_info") && root.path("owner_business_info").has("id")) {
                    String bmId = root.path("owner_business_info").path("id").asText();
                    log.info("[Commerce] Resolved Meta Business Portfolio ID {} for WABA {}", bmId, wabaId);
                    return bmId;
                }
            } catch (Exception e) {
                log.warn("[Commerce] Could not resolve owner_business_info for WABA {}: {}", wabaId, e.getMessage());
            }
        }

        try {
            String meUrl = String.format("%s/me/businesses?access_token=%s", getGraphBaseUrl(), accessToken);
            JsonNode root = executeGet(meUrl, accessToken);
            if (root != null && root.has("data") && root.path("data").isArray() && root.path("data").size() > 0) {
                String bmId = root.path("data").get(0).path("id").asText();
                log.info("[Commerce] Resolved Meta Business Portfolio ID {} from /me/businesses", bmId);
                return bmId;
            }
        } catch (Exception e) {
            log.warn("[Commerce] Could not resolve business from /me/businesses: {}", e.getMessage());
        }

        return null;
    }

    /**
     * List all connected catalogs for a WABA.
     */
    public JsonNode listConnectedCatalogs(String wabaId, String accessToken) {
        String url = String.format("%s/%s/product_catalogs", getGraphBaseUrl(), wabaId);
        return executeGet(url, accessToken);
    }

    /**
     * Connect a catalog to a WABA.
     */
    public JsonNode connectCatalog(String wabaId, String catalogId, String accessToken) {
        String url = String.format("%s/%s/product_catalogs", getGraphBaseUrl(), wabaId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("catalog_id", catalogId);
        return executePost(url, payload, accessToken);
    }

    /**
     * Disconnect a catalog from a WABA.
     */
    public void disconnectCatalog(String wabaId, String catalogId, String accessToken) {
        String url = String.format("%s/%s/product_catalogs?catalog_id=%s", getGraphBaseUrl(), wabaId, catalogId);
        executeDelete(url, accessToken);
    }

    /**
     * Get Commerce Settings for a Phone Number ID.
     */
    public JsonNode getCommerceSettings(String phoneNumberId, String accessToken) {
        String url = String.format("%s/%s/whatsapp_commerce_settings", getGraphBaseUrl(), phoneNumberId);
        return executeGet(url, accessToken);
    }

    /**
     * Update Commerce Settings (e.g. cart_enabled, catalog_visible) for a Phone Number ID.
     */
    public JsonNode updateCommerceSettings(String phoneNumberId, boolean cartEnabled, boolean catalogVisible, String accessToken) {
        String url = String.format("%s/%s/whatsapp_commerce_settings", getGraphBaseUrl(), phoneNumberId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("is_cart_enabled", cartEnabled);
        payload.put("is_catalog_visible", catalogVisible);
        return executePost(url, payload, accessToken);
    }

    /**
     * Create a new catalog for a Business Manager.
     */
    public JsonNode createCatalog(String businessId, String name, String accessToken) {
        String url = String.format("%s/%s/owned_product_catalogs", getGraphBaseUrl(), businessId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        return executePost(url, payload, accessToken);
    }

    /**
     * Create a new product in a Catalog.
     */
    public JsonNode createProduct(String catalogId, Map<String, Object> productData, String accessToken) {
        String url = String.format("%s/%s/products", getGraphBaseUrl(), catalogId);
        return executePost(url, productData, accessToken);
    }

    /**
     * Fetch products from a Catalog.
     */
    public JsonNode syncProducts(String catalogId, String accessToken) {
        String url = String.format("%s/%s/products?fields=id,retailer_id,name,description,image_url,additional_image_urls,price,sale_price,currency,url,category,availability,condition", getGraphBaseUrl(), catalogId);
        return executeGet(url, accessToken);
    }

    public record MetaCommerceError(
        String code,
        String errorSubcode,
        String fbtraceId,
        String errorUserTitle,
        String errorUserMsg
    ) {}

    public static class MetaCommerceApiException extends RuntimeException {
        private final MetaCommerceError commerceError;

        public MetaCommerceApiException(String message, MetaCommerceError commerceError) {
            super(message);
            this.commerceError = commerceError;
        }

        public MetaCommerceError getCommerceError() {
            return commerceError;
        }
    }

    public MetaCommerceError parseMetaCommerceError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return new MetaCommerceError("UNKNOWN", null, null, "Error", "No error details returned from Meta.");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode errorNode = root.path("error");
            if (errorNode.isMissingNode()) {
                return new MetaCommerceError("UNKNOWN", null, null, "Error", responseBody);
            }
            String code = errorNode.path("code").asText("");
            String subcode = errorNode.has("error_subcode") ? errorNode.path("error_subcode").asText() : null;
            String fbtraceId = errorNode.path("fbtrace_id").asText("");
            String userTitle = errorNode.path("error_user_title").asText("Commerce Error");
            String userMsg = errorNode.path("error_user_msg").asText("");

            if ("2388004".equals(subcode)) {
                userMsg = "Catalog ID is invalid or not eligible for this WhatsApp Business Account. Possible causes: " +
                        "(1) The catalog ID is incorrect or does not exist on Meta. " +
                        "(2) The catalog belongs to a different Meta Business Manager. " +
                        "(3) The access token does not have catalog management permissions. " +
                        "(4) The WABA is not properly associated with the catalog. " +
                        "Support reference: fbtrace_id=" + fbtraceId;
            } else if (userMsg.isBlank()) {
                userMsg = errorNode.path("message").asText("Meta Commerce API call failed.");
            }

            return new MetaCommerceError(code, subcode, fbtraceId, userTitle, userMsg);
        } catch (Exception e) {
            return new MetaCommerceError("UNKNOWN", null, null, "Error", responseBody);
        }
    }

    private JsonNode executeGet(String url, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException e) {
            long duration = System.currentTimeMillis() - start;
            MetaCommerceError error = parseMetaCommerceError(e.getResponseBodyAsString());
            log.error("[Commerce] operation=GET url={} durationMs={} errorCode={} errorSubcode={} fbtraceId={} userMsg={}",
                    url, duration, error.code(), error.errorSubcode(), error.fbtraceId(), error.errorUserMsg());
            throw new MetaCommerceApiException(error.errorUserMsg(), error);
        } catch (Exception e) {
            throw new RuntimeException("Meta API Error: " + e.getMessage(), e);
        }
    }

    private JsonNode executePost(String url, Map<String, Object> payload, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException e) {
            long duration = System.currentTimeMillis() - start;
            MetaCommerceError error = parseMetaCommerceError(e.getResponseBodyAsString());
            log.error("[Commerce] operation=POST url={} durationMs={} errorCode={} errorSubcode={} fbtraceId={} userMsg={}",
                    url, duration, error.code(), error.errorSubcode(), error.fbtraceId(), error.errorUserMsg());
            throw new MetaCommerceApiException(error.errorUserMsg(), error);
        } catch (Exception e) {
            throw new RuntimeException("Meta API Error: " + e.getMessage(), e);
        }
    }

    private void executeDelete(String url, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        long start = System.currentTimeMillis();
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, request, String.class);
        } catch (HttpStatusCodeException e) {
            long duration = System.currentTimeMillis() - start;
            MetaCommerceError error = parseMetaCommerceError(e.getResponseBodyAsString());
            log.error("[Commerce] operation=DELETE url={} durationMs={} errorCode={} errorSubcode={} fbtraceId={} userMsg={}",
                    url, duration, error.code(), error.errorSubcode(), error.fbtraceId(), error.errorUserMsg());
            throw new MetaCommerceApiException(error.errorUserMsg(), error);
        } catch (Exception e) {
            throw new RuntimeException("Meta API Error: " + e.getMessage(), e);
        }
    }
}
