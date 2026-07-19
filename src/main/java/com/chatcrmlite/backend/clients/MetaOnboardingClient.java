package com.chatcrmlite.backend.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class MetaOnboardingClient {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${meta.app.id:}")
    private String appId;

    @Value("${meta.app-secret:}")
    private String appSecret;

    private static final String GRAPH_API_BASE = "https://graph.facebook.com/v19.0";

    /**
     * Exchanges a short-lived token for a long-lived system user token.
     */
    public String exchangeForLongLivedToken(String code) {
        String url = String.format("%s/oauth/access_token?client_id=%s&client_secret=%s&code=%s",
                GRAPH_API_BASE, appId, appSecret, code);
        
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            return root.path("access_token").asText();
        } catch (Exception e) {
            log.error("Failed to exchange long-lived token", e);
            throw new RuntimeException("Failed to exchange Meta access token", e);
        }
    }

    /**
     * Retrieves the Business ID and Token Expiry from the debug_token endpoint.
     */
    public Map<String, Object> debugToken(String inputToken) {
        // App access token is appId|appSecret
        String appAccessToken = appId + "|" + appSecret;
        String url = String.format("%s/debug_token?input_token=%s&access_token=%s", GRAPH_API_BASE, inputToken, appAccessToken);

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode data = objectMapper.readTree(response.getBody()).path("data");
            
            Map<String, Object> result = new HashMap<>();
            
            // Extract Business ID and WABA ID if they exist
            if (data.has("granular_scopes")) {
                for (JsonNode scope : data.path("granular_scopes")) {
                    String scopeName = scope.path("scope").asText();
                    if (scope.has("target_ids") && scope.path("target_ids").size() > 0) {
                        if ("whatsapp_business_management".equals(scopeName)) {
                            result.put("businessId", scope.path("target_ids").get(0).asText());
                        } else if ("whatsapp_business_messaging".equals(scopeName)) {
                            result.put("wabaId", scope.path("target_ids").get(0).asText());
                        }
                    }
                }
            }
            
            // Extract expiry
            if (data.has("expires_at")) {
                long expiresAt = data.path("expires_at").asLong();
                if (expiresAt > 0) {
                    result.put("tokenExpiry", LocalDateTime.ofEpochSecond(expiresAt, 0, ZoneOffset.UTC));
                }
            }
            
            return result;
        } catch (Exception e) {
            log.error("Failed to debug token", e);
            throw new RuntimeException("Failed to fetch token metadata from Meta", e);
        }
    }

    /**
     * Fetches WABA ID for a given Business ID.
     */
    public String fetchWabaId(String businessId, String accessToken) {
        String url = String.format("%s/%s/owned_whatsapp_business_accounts?access_token=%s", GRAPH_API_BASE, businessId, accessToken);
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode data = objectMapper.readTree(response.getBody()).path("data");
            if (data.isArray() && data.size() > 0) {
                return data.get(0).path("id").asText();
            }
            throw new RuntimeException("No WABA found for this Business ID");
        } catch (Exception e) {
            log.error("Failed to fetch WABA ID", e);
            throw new RuntimeException("Failed to fetch WABA ID from Meta", e);
        }
    }

    /**
     * Fetches Phone Number details (ID, Display Number, Verified Name, Quality, Tier) for a WABA.
     */
    public Map<String, String> fetchPhoneNumberDetails(String wabaId, String accessToken) {
        String url = String.format("%s/%s/phone_numbers?access_token=%s", GRAPH_API_BASE, wabaId, accessToken);
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode data = objectMapper.readTree(response.getBody()).path("data");
            if (data.isArray() && data.size() > 0) {
                JsonNode phoneNode = data.get(0);
                Map<String, String> details = new HashMap<>();
                details.put("phoneNumberId", phoneNode.path("id").asText());
                details.put("displayPhoneNumber", phoneNode.path("display_phone_number").asText());
                details.put("verifiedName", phoneNode.path("verified_name").asText());
                details.put("qualityRating", phoneNode.path("quality_rating").asText());
                return details;
            }
            throw new RuntimeException("No Phone Numbers found in this WABA");
        } catch (Exception e) {
            log.error("Failed to fetch Phone Number details", e);
            throw new RuntimeException("Failed to fetch Phone Number details from Meta", e);
        }
    }
}
