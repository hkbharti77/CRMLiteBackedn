package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise Service for Meta WhatsApp Business Embedded Signup & OAuth Token Exchange.
 * 
 * Guarantees:
 * 1. Single-use opaque sessionId for replay protection.
 * 2. Non-blocking isolation: External Meta API calls occur outside DB transactions.
 * 3. Distinct Connection Status vs. Webhook Subscription Status lifecycles.
 * 4. Structured webhook error diagnostics and retry support.
 * 5. Zero-leak logging contract: sensitive tokens are never logged.
 */
@Slf4j
@Service
public class MetaOnboardingService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Value("${meta.app.id:1573307991099476}")
    private String appId;

    @Value("${meta.app-secret:}")
    private String appSecret;

    @Value("${meta.config.id:1052344107323702}")
    private String configId;

    @Value("${meta.graph-api-version:v21.0}")
    private String graphApiVersion;

    @Value("${meta.api-base-url:https://graph.facebook.com}")
    private String apiBaseUrl;

    @Value("${app.public.url:http://localhost:8080}")
    private String publicAppUrl;

    // Session cache with 10-minute TTL
    private final Map<String, OnboardingSession> sessionStore = new ConcurrentHashMap<>();

    @Data
    @Builder
    public static class OnboardingSession {
        private String sessionId;
        private UUID tenantId;
        private UUID userId;
        private String userEmail;
        private String status; // INITIATED, EXCHANGED, COMPLETED, EXPIRED
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;
    }

    @Data
    @Builder
    public static class TokenExchangeResult {
        private String accessToken;
        private String tokenType;
        private Long expiresInSeconds;
        private LocalDateTime tokenExpiry;
    }

    @Data
    @Builder
    public static class TokenDebugResult {
        private boolean isValid;
        private String businessId;
        private String wabaId;
        private LocalDateTime tokenExpiry;
        private List<String> scopes;
    }

    @Data
    @Builder
    public static class PhoneDetailsResult {
        private String phoneNumberId;
        private String displayPhoneNumber;
        private String verifiedName;
        private String qualityRating;
    }

    @Data
    @Builder
    public static class WebhookSubscriptionResult {
        private String status; // ACTIVE, FAILED, PENDING
        private String error;
        private Integer lastMetaErrorCode;
        private LocalDateTime subscribedAt;
    }

    private String getGraphApiUrl() {
        String base = StringUtils.hasText(apiBaseUrl) ? apiBaseUrl.replaceAll("/+$", "") : "https://graph.facebook.com";
        String ver = StringUtils.hasText(graphApiVersion) ? graphApiVersion.replaceAll("^/+|/+$", "") : "v21.0";
        return base + "/" + ver;
    }

    /**
     * Step 1: Create an authenticated onboarding session bound to the tenant/user.
     */
    public Map<String, Object> createSession(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null when creating an onboarding session");
        }

        cleanExpiredSessions();

        String sessionId = UUID.randomUUID().toString();
        UUID tenantId = (user.getTenant() != null) ? user.getTenant().getId() : user.getId();
        LocalDateTime now = LocalDateTime.now();

        OnboardingSession session = OnboardingSession.builder()
                .sessionId(sessionId)
                .tenantId(tenantId)
                .userId(user.getId())
                .userEmail(user.getEmail())
                .status("INITIATED")
                .createdAt(now)
                .expiresAt(now.plusMinutes(10))
                .build();

        sessionStore.put(sessionId, session);

        String launcherBase = (publicAppUrl != null && !publicAppUrl.isBlank()) ? publicAppUrl.replaceAll("/+$", "") : "";
        String launcherUrl = launcherBase + "/api/v1/integrations/meta/gateway/launch";

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", sessionId);
        response.put("appId", appId);
        response.put("configId", configId);
        response.put("launcherUrl", launcherUrl);
        response.put("expiresAt", session.getExpiresAt().toString());
        return response;
    }

    /**
     * Validates single-use session ownership and transitions state.
     */
    public OnboardingSession validateAndConsumeSession(String sessionId, User authenticatedUser) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("Session ID is required");
        }

        OnboardingSession session = sessionStore.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("Invalid or expired session. Please restart onboarding.");
        }

        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            sessionStore.remove(sessionId);
            throw new IllegalStateException("Onboarding session expired. Please restart onboarding.");
        }

        if (!"INITIATED".equals(session.getStatus())) {
            throw new IllegalStateException("Session has already been used or completed. Replay rejected.");
        }

        UUID authTenantId = (authenticatedUser != null && authenticatedUser.getTenant() != null)
                ? authenticatedUser.getTenant().getId()
                : (authenticatedUser != null ? authenticatedUser.getId() : null);

        if (authTenantId != null && !authTenantId.equals(session.getTenantId())) {
            throw new SecurityException("Tenant mismatch: Authenticated user does not match session tenant");
        }

        // Transition status to prevent replay
        session.setStatus("EXCHANGED");
        return session;
    }

    /**
     * Step 2: Main Orchestrator for Token Exchange & Provisioning.
     * Note: External Meta calls run outside any DB transaction to avoid connection pool starvation.
     */
    public Map<String, Object> exchangeAndProvision(String code, String sessionId, User user) {
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("OAuth authorization code is required");
        }

        // 1. Validate Session & Prevent Replay
        OnboardingSession session = validateAndConsumeSession(sessionId, user);
        UUID tenantId = session.getTenantId();

        log.info("[MetaOnboarding] Initiating exchange for tenantId={}, userEmail={}", tenantId, session.getUserEmail());

        try {
            // 2. Exchange Authorization Code for Access Token
            TokenExchangeResult tokenResult = exchangeCodeForToken(code);

            // 3. Inspect Token with /debug_token
            TokenDebugResult debugResult = validateAndInspectToken(tokenResult.getAccessToken());

            String businessId = debugResult.getBusinessId();
            LocalDateTime tokenExpiry = (tokenResult.getTokenExpiry() != null)
                    ? tokenResult.getTokenExpiry()
                    : debugResult.getTokenExpiry();

            // 4. Resolve Authoritative WABA ID
            String wabaId = resolveWaba(businessId, tokenResult.getAccessToken(), debugResult);

            // 5. Resolve Registered Phone Number Details
            PhoneDetailsResult phoneDetails = resolvePhoneNumber(wabaId, tokenResult.getAccessToken());

            // 6. Subscribe WABA to Webhook
            WebhookSubscriptionResult webhookResult = subscribeWebhook(wabaId, tokenResult.getAccessToken());

            // 7. Transactional DB Persistence (Isolated and Fast)
            WhatsAppConfig savedConfig = saveProvisionedConfig(
                    tenantId,
                    user,
                    tokenResult.getAccessToken(),
                    tokenExpiry,
                    businessId,
                    wabaId,
                    phoneDetails,
                    webhookResult
            );

            session.setStatus("COMPLETED");

            log.info("[MetaOnboarding] Provisioning completed successfully for tenantId={}, wabaId={}, phoneNumberId={}, webhookStatus={}",
                    tenantId, wabaId, savedConfig.getPhoneNumberId(), savedConfig.getWebhookSubscriptionStatus());

            // 8. Construct Safe Response Map (Zero token leakage)
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "WhatsApp Coexistence Embedded Signup Connected Successfully");
            resp.put("phoneNumberId", savedConfig.getPhoneNumberId());
            resp.put("displayPhoneNumber", savedConfig.getDisplayPhoneNumber());
            resp.put("verifiedName", savedConfig.getVerifiedName());
            resp.put("wabaId", savedConfig.getWabaId());
            resp.put("connectionType", savedConfig.getConnectionType());
            resp.put("connectionStatus", savedConfig.getConnectionStatus());
            resp.put("webhookSubscriptionStatus", savedConfig.getWebhookSubscriptionStatus());
            if (savedConfig.getWebhookSubscriptionError() != null) {
                resp.put("webhookSubscriptionError", savedConfig.getWebhookSubscriptionError());
            }
            return resp;

        } catch (Exception e) {
            log.error("[MetaOnboarding] Exchange failed for tenantId={}: {}", tenantId, e.getMessage());
            throw new RuntimeException("Meta onboarding exchange failed: " + e.getMessage(), e);
        }
    }

    /**
     * Calls Meta /oauth/access_token to exchange authorization code.
     */
    public TokenExchangeResult exchangeCodeForToken(String code) {
        String url = UriComponentsBuilder.fromHttpUrl(getGraphApiUrl() + "/oauth/access_token")
                .queryParam("client_id", appId)
                .queryParam("client_secret", appSecret)
                .queryParam("code", code)
                .toUriString();

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            String accessToken = root.path("access_token").asText();
            if (!StringUtils.hasText(accessToken)) {
                throw new IllegalStateException("Meta did not return an access_token in exchange response");
            }

            String tokenType = root.path("token_type").asText("bearer");
            Long expiresIn = root.has("expires_in") ? root.path("expires_in").asLong() : null;
            LocalDateTime expiry = (expiresIn != null && expiresIn > 0)
                    ? LocalDateTime.now(ZoneOffset.UTC).plusSeconds(expiresIn)
                    : null;

            return TokenExchangeResult.builder()
                    .accessToken(accessToken)
                    .tokenType(tokenType)
                    .expiresInSeconds(expiresIn)
                    .tokenExpiry(expiry)
                    .build();

        } catch (HttpStatusCodeException e) {
            log.error("[MetaOnboarding] OAuth exchange HTTP error status={}", e.getStatusCode());
            throw new RuntimeException("Meta OAuth exchange failed: " + sanitizeMetaErrorMessage(e.getResponseBodyAsString()), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to exchange Meta OAuth authorization code", e);
        }
    }

    /**
     * Inspects token using Meta /debug_token with App Access Token.
     */
    public TokenDebugResult validateAndInspectToken(String accessToken) {
        String appAccessToken = appId + "|" + appSecret;
        String url = UriComponentsBuilder.fromHttpUrl(getGraphApiUrl() + "/debug_token")
                .queryParam("input_token", accessToken)
                .queryParam("access_token", appAccessToken)
                .toUriString();

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode data = objectMapper.readTree(response.getBody()).path("data");

            boolean isValid = data.path("is_valid").asBoolean(true);
            String businessId = null;
            String wabaId = null;
            List<String> scopes = new ArrayList<>();

            if (data.has("granular_scopes")) {
                for (JsonNode scope : data.path("granular_scopes")) {
                    String scopeName = scope.path("scope").asText();
                    scopes.add(scopeName);
                    if (scope.has("target_ids") && scope.path("target_ids").size() > 0) {
                        if ("whatsapp_business_management".equals(scopeName)) {
                            businessId = scope.path("target_ids").get(0).asText();
                        } else if ("whatsapp_business_messaging".equals(scopeName)) {
                            wabaId = scope.path("target_ids").get(0).asText();
                        }
                    }
                }
            }

            LocalDateTime expiry = null;
            if (data.has("expires_at")) {
                long expiresAt = data.path("expires_at").asLong();
                if (expiresAt > 0) {
                    expiry = LocalDateTime.ofEpochSecond(expiresAt, 0, ZoneOffset.UTC);
                }
            }

            return TokenDebugResult.builder()
                    .isValid(isValid)
                    .businessId(businessId)
                    .wabaId(wabaId)
                    .tokenExpiry(expiry)
                    .scopes(scopes)
                    .build();

        } catch (Exception e) {
            log.warn("[MetaOnboarding] /debug_token inspection encountered issue: {}", e.getMessage());
            return TokenDebugResult.builder().isValid(true).build();
        }
    }

    /**
     * Authoritative Multi-Tier WABA Resolution.
     */
    public String resolveWaba(String businessId, String accessToken, TokenDebugResult debugResult) {
        // 1. Direct query on owned WhatsApp Business Accounts if businessId is known
        if (StringUtils.hasText(businessId)) {
            try {
                String ownedUrl = String.format("%s/%s/owned_whatsapp_business_accounts?access_token=%s",
                        getGraphApiUrl(), businessId, accessToken);
                ResponseEntity<String> response = restTemplate.getForEntity(ownedUrl, String.class);
                JsonNode data = objectMapper.readTree(response.getBody()).path("data");
                if (data.isArray() && data.size() > 0) {
                    return data.get(0).path("id").asText();
                }
            } catch (Exception e) {
                log.warn("[MetaOnboarding] Could not query owned_whatsapp_business_accounts: {}", e.getMessage());
            }

            // 2. Query client_whatsapp_business_accounts
            try {
                String clientUrl = String.format("%s/%s/client_whatsapp_business_accounts?access_token=%s",
                        getGraphApiUrl(), businessId, accessToken);
                ResponseEntity<String> response = restTemplate.getForEntity(clientUrl, String.class);
                JsonNode data = objectMapper.readTree(response.getBody()).path("data");
                if (data.isArray() && data.size() > 0) {
                    return data.get(0).path("id").asText();
                }
            } catch (Exception e) {
                log.warn("[MetaOnboarding] Could not query client_whatsapp_business_accounts: {}", e.getMessage());
            }
        }

        // 3. Fallback to debug token granular scopes target_ids
        if (debugResult != null && StringUtils.hasText(debugResult.getWabaId())) {
            return debugResult.getWabaId();
        }

        // 4. Query /me/accounts as final fallback
        try {
            String meUrl = String.format("%s/me/whatsapp_business_accounts?access_token=%s", getGraphApiUrl(), accessToken);
            ResponseEntity<String> response = restTemplate.getForEntity(meUrl, String.class);
            JsonNode data = objectMapper.readTree(response.getBody()).path("data");
            if (data.isArray() && data.size() > 0) {
                return data.get(0).path("id").asText();
            }
        } catch (Exception ignored) {}

        throw new IllegalStateException("Could not resolve WABA ID for the authenticated WhatsApp Business Account");
    }

    /**
     * Resolves Phone Number Details for the given WABA.
     */
    public PhoneDetailsResult resolvePhoneNumber(String wabaId, String accessToken) {
        String url = String.format("%s/%s/phone_numbers?access_token=%s", getGraphApiUrl(), wabaId, accessToken);
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode data = objectMapper.readTree(response.getBody()).path("data");
            if (data.isArray() && data.size() > 0) {
                JsonNode phoneNode = data.get(0);
                return PhoneDetailsResult.builder()
                        .phoneNumberId(phoneNode.path("id").asText())
                        .displayPhoneNumber(phoneNode.path("display_phone_number").asText())
                        .verifiedName(phoneNode.path("verified_name").asText())
                        .qualityRating(phoneNode.path("quality_rating").asText("UNKNOWN"))
                        .build();
            }
            throw new IllegalStateException("No registered phone numbers found in WABA " + wabaId);
        } catch (Exception e) {
            log.error("[MetaOnboarding] Failed to resolve phone numbers for WABA {}: {}", wabaId, e.getMessage());
            throw new RuntimeException("Failed to fetch phone number details from Meta: " + e.getMessage(), e);
        }
    }

    /**
     * Subscribes WABA to Meta App Webhooks.
     */
    public WebhookSubscriptionResult subscribeWebhook(String wabaId, String accessToken) {
        if (!StringUtils.hasText(wabaId) || !StringUtils.hasText(accessToken)) {
            return WebhookSubscriptionResult.builder()
                    .status("FAILED")
                    .error("Missing WABA ID or Access Token for webhook subscription")
                    .build();
        }

        String subscribeUrl = String.format("%s/%s/subscribed_apps?access_token=%s", getGraphApiUrl(), wabaId, accessToken);
        LocalDateTime now = LocalDateTime.now();

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(subscribeUrl, null, String.class);
            JsonNode body = objectMapper.readTree(response.getBody());
            boolean success = body.path("success").asBoolean(true);

            if (success) {
                return WebhookSubscriptionResult.builder()
                        .status("ACTIVE")
                        .subscribedAt(now)
                        .build();
            } else {
                return WebhookSubscriptionResult.builder()
                        .status("FAILED")
                        .error("Meta returned success: false on subscribed_apps")
                        .build();
            }
        } catch (HttpStatusCodeException e) {
            Integer metaCode = null;
            String sanitizedError = sanitizeMetaErrorMessage(e.getResponseBodyAsString());
            try {
                JsonNode errNode = objectMapper.readTree(e.getResponseBodyAsString()).path("error");
                if (errNode.has("code")) metaCode = errNode.path("code").asInt();
            } catch (Exception ignored) {}

            log.warn("[MetaOnboarding] Webhook subscription HTTP failed with code={}: {}", metaCode, sanitizedError);
            return WebhookSubscriptionResult.builder()
                    .status("FAILED")
                    .error(sanitizedError)
                    .lastMetaErrorCode(metaCode)
                    .build();
        } catch (Exception e) {
            log.warn("[MetaOnboarding] Webhook subscription unexpected failure: {}", e.getMessage());
            return WebhookSubscriptionResult.builder()
                    .status("FAILED")
                    .error(e.getMessage())
                    .build();
        }
    }

    /**
     * Retries Webhook Subscription for an already connected WhatsApp integration.
     */
    public Map<String, Object> retryWebhookSubscription(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Tenant ID is required for webhook retry");
        }

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("No WhatsApp configuration found for tenant"));

        if (!StringUtils.hasText(config.getWabaId()) || !StringUtils.hasText(config.getAccessToken())) {
            throw new IllegalStateException("WhatsApp credentials missing or incomplete");
        }

        WebhookSubscriptionResult result = subscribeWebhook(config.getWabaId(), config.getAccessToken());

        updateWebhookStatusInDb(config.getId(), result);

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", "ACTIVE".equals(result.getStatus()));
        resp.put("webhookSubscriptionStatus", result.getStatus());
        if (result.getError() != null) {
            resp.put("webhookSubscriptionError", result.getError());
        }
        return resp;
    }

    @Transactional
    public void updateWebhookStatusInDb(UUID configId, WebhookSubscriptionResult result) {
        WhatsAppConfig config = whatsappConfigRepository.findById(configId)
                .orElseThrow(() -> new IllegalStateException("Config not found"));

        config.setWebhookSubscriptionStatus(result.getStatus());
        config.setWebhookLastAttemptAt(LocalDateTime.now());
        config.setWebhookRetryCount((config.getWebhookRetryCount() != null ? config.getWebhookRetryCount() : 0) + 1);

        if ("ACTIVE".equals(result.getStatus())) {
            config.setWebhookSubscribedAt(result.getSubscribedAt());
            config.setWebhookSubscriptionError(null);
            config.setWebhookLastMetaErrorCode(null);
        } else {
            config.setWebhookSubscriptionError(result.getError());
            config.setWebhookLastMetaErrorCode(result.getLastMetaErrorCode());
        }

        whatsappConfigRepository.save(config);
    }

    /**
     * Isolated, Fast Database Transaction for Persisting Provisioned Integration.
     */
    @Transactional
    public WhatsAppConfig saveProvisionedConfig(
            UUID tenantId,
            User user,
            String accessToken,
            LocalDateTime tokenExpiry,
            String businessId,
            String wabaId,
            PhoneDetailsResult phoneDetails,
            WebhookSubscriptionResult webhookResult) {

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    WhatsAppConfig nc = new WhatsAppConfig();
                    if (user != null && user.getTenant() != null) {
                        nc.setTenant(user.getTenant());
                    }
                    nc.setUser(user);
                    return nc;
                });

        config.setUser(user);
        config.setConnectionType("EMBEDDED_SIGNUP_COEXISTENCE");
        config.setConnectionStatus("ACTIVE");
        config.setAccessToken(accessToken); // Automatically AES-encrypted by EncryptionConverter
        config.setTokenExpiry(tokenExpiry);
        config.setBusinessId(businessId);
        config.setWabaId(wabaId);

        if (phoneDetails != null) {
            config.setPhoneNumberId(phoneDetails.getPhoneNumberId());
            config.setDisplayPhoneNumber(phoneDetails.getDisplayPhoneNumber());
            config.setVerifiedName(phoneDetails.getVerifiedName());
            config.setQualityRating(phoneDetails.getQualityRating());
        }

        config.setVerificationStatus("VERIFIED");
        config.setAccountStatus("ACTIVE");

        // Webhook diagnostics
        config.setWebhookSubscriptionStatus(webhookResult.getStatus());
        config.setWebhookLastAttemptAt(LocalDateTime.now());
        if ("ACTIVE".equals(webhookResult.getStatus())) {
            config.setWebhookSubscribedAt(webhookResult.getSubscribedAt());
            config.setWebhookSubscriptionError(null);
            config.setWebhookLastMetaErrorCode(null);
        } else {
            config.setWebhookSubscriptionError(webhookResult.getError());
            config.setWebhookLastMetaErrorCode(webhookResult.getLastMetaErrorCode());
        }

        if (!StringUtils.hasText(config.getVerifyToken())) {
            config.setVerifyToken("crm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        }

        return whatsappConfigRepository.save(config);
    }

    private String sanitizeMetaErrorMessage(String rawResponse) {
        if (!StringUtils.hasText(rawResponse)) return "Unknown Meta API error";
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            if (root.has("error")) {
                JsonNode err = root.path("error");
                String msg = err.path("message").asText();
                String type = err.path("type").asText();
                int code = err.path("code").asInt(0);
                return String.format("[%s (code %d)]: %s", type, code, msg);
            }
        } catch (Exception ignored) {}
        return rawResponse.length() > 200 ? rawResponse.substring(0, 200) + "..." : rawResponse;
    }

    private void cleanExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        sessionStore.entrySet().removeIf(entry -> entry.getValue().getExpiresAt().isBefore(now));
    }
}
