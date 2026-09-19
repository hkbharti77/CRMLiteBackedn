package com.chatcrmlite.backend.clients;

import com.chatcrmlite.backend.dto.flows.FlowAssetUploadResult;
import com.chatcrmlite.backend.dto.flows.FlowDeprecateResult;
import com.chatcrmlite.backend.dto.flows.FlowPublishResult;
import com.chatcrmlite.backend.dto.flows.FlowValidationError;
import com.chatcrmlite.backend.exceptions.MetaFlowResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP client for Meta's WhatsApp Flow Graph API.
 *
 * <p>All methods return structured result objects rather than throwing exceptions for non-2xx
 * responses. This is because Spring RestTemplate throws {@code RestClientResponseException}
 * <em>before</em> response parsing runs, making HTTP status codes otherwise unreachable in
 * the caller's normal code path.
 *
 * <p>Callers (e.g. {@code FlowPublishWorker}) use the result objects to classify errors as
 * terminal, retryable, or requiring reconciliation — without catching raw exceptions.
 */
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
        String ver  = (apiVersion != null && !apiVersion.isBlank())  ? apiVersion.trim()  : "v21.0";
        if (!base.endsWith("/")) base += "/";
        if (ver.startsWith("/")) ver = ver.substring(1);
        if (!ver.endsWith("/")) ver += "/";
        if (path.startsWith("/")) path = path.substring(1);
        return base + ver + path;
    }

    // ── Create ─────────────────────────────────────────────────────────────────

    /**
     * Creates a new Meta Flow container on the WABA.
     *
     * @param wabaId       the WABA to create the flow under
     * @param metaName     revision-unique name (NOT the display name) —
     *                     format: "{displayName[0..40]}--r-{revisionId[0..16] hex}"
     * @param categories   e.g. ["LEAD_GENERATION"]
     * @param accessToken  WABA access token
     * @param cloneFlowId  optional: if set, Meta clones this Flow's structure as the starting point.
     *                     Pass the previous published revision's meta_flow_id on re-publish.
     *                     Pass null on the very first publish.
     * @return the newly created Meta Flow ID
     * @throws RuntimeException on terminal creation failure (400/401/403)
     */
    public String createFlowContainer(String wabaId, String metaName, List<String> categories,
                                      String accessToken, @Nullable String cloneFlowId) {
        String url = buildUrl(wabaId + "/flows");
        log.info("🚀 [MetaFlowClient] Creating Flow container: WABA={} metaName='{}' clone={}",
                wabaId, metaName, cloneFlowId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("name", metaName);
        body.put("categories", categories);
        if (cloneFlowId != null && !cloneFlowId.isBlank()) {
            body.put("clone_flow_id", cloneFlowId);   // Meta's documented versioning mechanism
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            String flowId = root.path("id").asText();
            if (flowId == null || flowId.isBlank()) {
                throw new IllegalStateException("Meta API returned empty Flow ID: " + response.getBody());
            }
            log.info("✅ [MetaFlowClient] Created Meta Flow container ID: {}", flowId);
            return flowId;

        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String body2 = ex.getResponseBodyAsString();
            log.error("❌ [MetaFlowClient] createFlowContainer HTTP {}: {}", status, body2);
            // Propagate as RuntimeException — caller classifies terminal vs retryable
            throw new RuntimeException("Meta create error " + status + ": " + parseMetaErrorMessage(body2), ex);

        } catch (ResourceAccessException ex) {
            log.error("❌ [MetaFlowClient] createFlowContainer timeout/network error", ex);
            throw new RuntimeException("Network/timeout error creating Flow", ex);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new RuntimeException("JSON parsing error", ex);
        }
    }

    // ── Reconciliation helper ──────────────────────────────────────────────────

    /**
     * Looks up an existing Meta Flow container by its deterministic {@code metaName}.
     *
     * <p>Used for timeout recovery: if {@code createFlowContainer} times out, calling this
     * method with the same {@code metaName} uniquely identifies whether Meta committed the
     * creation (returns the Flow ID) or not (returns empty).
     *
     * <p>Because {@code metaName} embeds the revision UUID prefix, it is guaranteed unique
     * per revision — unlike the display name, which can be shared across revisions.
     */
    public Optional<String> findCreatedFlowByName(String wabaId, String metaName, String accessToken) {
        String found = findExistingFlowIdByName(wabaId, metaName, accessToken);
        return Optional.ofNullable(found);
    }

    /**
     * Searches for an existing Meta Flow by exact name match within a WABA.
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
            log.warn("⚠️ [MetaFlowClient] findExistingFlowIdByName failed for WABA {}: {}", wabaId, e.getMessage());
        }
        return null;
    }

    // ── Upload ─────────────────────────────────────────────────────────────────

    /**
     * Uploads the flow.json asset to Meta for validation and compilation.
     *
     * <p>Returns a structured {@link FlowAssetUploadResult} in all cases — including
     * non-2xx responses — so the caller can classify errors without catching exceptions.
     *
     * <p>Meta's response may contain {@code validation_errors} even when {@code success=true}.
     * Always check {@link FlowAssetUploadResult#hasValidationErrors()} after a successful upload.
     */
    public FlowAssetUploadResult uploadFlowAssets(String metaFlowId, String flowJson, String accessToken) {
        String url = buildUrl(metaFlowId + "/assets");
        log.info("📤 [MetaFlowClient] Uploading flow.json to Meta Flow ID: {}", metaFlowId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(accessToken);

        ByteArrayResource fileResource = new ByteArrayResource(flowJson.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "flow.json"; }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("name", "flow.json");
        body.add("asset_type", "FLOW_JSON");
        body.add("file", fileResource);

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            // Hardened: do NOT default success to true — a missing flag is a contract violation
            JsonNode successNode = root.get("success");
            if (successNode == null || !successNode.isBoolean()) {
                throw new MetaFlowResponseException(
                        "Meta asset upload response missing 'success' flag: " + response.getBody());
            }
            boolean success = successNode.asBoolean();

            List<FlowValidationError> errors = parseValidationErrors(root);
            if (!errors.isEmpty()) {
                log.warn("⚠️ [MetaFlowClient] Flow {} has {} validation error(s)", metaFlowId, errors.size());
            }
            log.info("✅ [MetaFlowClient] Uploaded flow.json for Meta Flow ID: {} success={}", metaFlowId, success);
            return FlowAssetUploadResult.success(errors, response.getBody());

        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String rawBody = ex.getResponseBodyAsString();
            String code = parseMetaErrorCode(rawBody);
            String msg  = parseMetaErrorMessage(rawBody);
            log.error("❌ [MetaFlowClient] uploadFlowAssets HTTP {}: {}", status, rawBody);
            return FlowAssetUploadResult.apiError(status, code, msg, rawBody);

        } catch (ResourceAccessException ex) {
            log.error("❌ [MetaFlowClient] uploadFlowAssets network error: {}", ex.getMessage());
            return FlowAssetUploadResult.networkError(ex.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return FlowAssetUploadResult.networkError("JSON parsing error: " + ex.getMessage());
        }
    }

    // ── Publish ────────────────────────────────────────────────────────────────

    /**
     * Publishes a Meta Flow, making it available to customers on WhatsApp.
     *
     * <p>Returns a structured {@link FlowPublishResult}. On timeout (httpStatus=0),
     * the caller should perform a GET to verify whether Meta committed the publish
     * before retrying.
     *
     * <p>Meta documents publishing as irreversible — once published, assets are immutable.
     */
    public FlowPublishResult publishFlow(String metaFlowId, String accessToken) {
        String url = buildUrl(metaFlowId + "/publish");
        log.info("📢 [MetaFlowClient] Publishing Meta Flow ID: {}", metaFlowId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        HttpEntity<String> request = new HttpEntity<>("{}", headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            log.info("✅ [MetaFlowClient] Published Meta Flow ID: {}", metaFlowId);
            return FlowPublishResult.success(response.getBody());

        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String rawBody = ex.getResponseBodyAsString();
            log.error("❌ [MetaFlowClient] publishFlow HTTP {}: {}", status, rawBody);
            return FlowPublishResult.apiError(status, parseMetaErrorCode(rawBody),
                    parseMetaErrorMessage(rawBody), rawBody);

        } catch (ResourceAccessException ex) {
            log.error("❌ [MetaFlowClient] publishFlow network error: {}", ex.getMessage());
            return FlowPublishResult.networkError(ex.getMessage());
        }
    }

    // ── Status / Details ───────────────────────────────────────────────────────

    /**
     * Fetches detailed flow information from Meta including status, health_status,
     * and validation_errors.
     *
     * <p>Used for:
     * <ul>
     *   <li>Post-publish verification (confirming Meta reports PUBLISHED)</li>
     *   <li>Pre-publish pre-check (detecting already-published or deprecated state)</li>
     *   <li>PUBLISH timeout recovery (verifying whether Meta committed the operation)</li>
     * </ul>
     */
    public JsonNode getFlowDetails(String metaFlowId, String accessToken) {
        String url = buildUrl(metaFlowId + "?fields=id,name,status,categories,validation_errors,health_status");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.warn("⚠️ [MetaFlowClient] getFlowDetails failed for flow {}: {}", metaFlowId, e.getMessage());
            return null;
        }
    }

    /**
     * Convenience wrapper — returns only the Meta status string from {@link #getFlowDetails}.
     * Returns null if the GET call fails.
     */
    @Nullable
    public String getFlowStatus(String metaFlowId, String accessToken) {
        JsonNode details = getFlowDetails(metaFlowId, accessToken);
        return (details != null) ? details.path("status").asText(null) : null;
    }

    // ── Deprecate ──────────────────────────────────────────────────────────────

    /**
     * Deprecates a published Meta Flow.
     *
     * <p>Uses the correct endpoint: {@code POST /{flow-id}/deprecate}.
     * Meta documents this operation as irreversible and only applicable to published flows.
     *
     * <p>On timeout (httpStatus=0), the caller should perform a GET on the old flow to verify
     * whether Meta committed the deprecation before scheduling a retry.
     */
    public FlowDeprecateResult deprecateFlow(String metaFlowId, String accessToken) {
        String url = buildUrl(metaFlowId + "/deprecate");   // ← correct endpoint (not /deprecation)
        log.info("🔒 [MetaFlowClient] Deprecating Meta Flow ID: {}", metaFlowId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        HttpEntity<String> request = new HttpEntity<>("{}", headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            log.info("✅ [MetaFlowClient] Deprecated Meta Flow ID: {}", metaFlowId);
            return FlowDeprecateResult.success(response.getBody());

        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String rawBody = ex.getResponseBodyAsString();
            log.warn("⚠️ [MetaFlowClient] deprecateFlow HTTP {}: {}", status, rawBody);
            return FlowDeprecateResult.apiError(status, parseMetaErrorCode(rawBody),
                    parseMetaErrorMessage(rawBody), rawBody);

        } catch (ResourceAccessException ex) {
            log.warn("⚠️ [MetaFlowClient] deprecateFlow network error: {}", ex.getMessage());
            return FlowDeprecateResult.networkError(ex.getMessage());
        }
    }

    // ── Fetch all WABA flows ───────────────────────────────────────────────────

    /**
     * Fetches all flows registered under a WABA from the Meta Graph API.
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
            log.error("❌ [MetaFlowClient] fetchWabaFlows failed for WABA {}: {}", wabaId, e.getMessage());
            throw new RuntimeException("Failed to fetch flows from Meta: " + e.getMessage(), e);
        }
    }

    // ── Legacy delete (kept for hard-delete of DRAFT flows) ───────────────────

    /**
     * Hard-deletes a Meta Flow (only valid for DRAFT flows).
     * For PUBLISHED flows, use {@link #deprecateFlow} instead.
     *
     * @deprecated Prefer {@link #deprecateFlow} for published flows.
     */
    public static class FlowStatusResult {
        private final String status;
        private final List<FlowValidationError> validationErrors;
        public FlowStatusResult(String status, List<FlowValidationError> validationErrors) {
            this.status = status;
            this.validationErrors = validationErrors;
        }
        public String getStatus() { return status; }
        public List<FlowValidationError> getValidationErrors() { return validationErrors; }
    }

    public FlowStatusResult fetchFlowStatus(String metaFlowId, String accessToken) {
        String url = buildUrl(metaFlowId + "?fields=status,validation_errors");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            String status = root.path("status").asText("UNKNOWN");
            List<FlowValidationError> errors = parseValidationErrors(root);
            return new FlowStatusResult(status, errors);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to fetch Flow status for " + metaFlowId, ex);
        }
    }

    // ── Deletion (Draft only) ──────────────────────────────────────────────────

    @Deprecated
    public void deleteFlow(String metaFlowId, String accessToken) {
        String url = buildUrl(metaFlowId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, request, String.class);
            log.info("🗑️ [MetaFlowClient] Deleted (DRAFT) Meta Flow: {}", metaFlowId);
        } catch (Exception e) {
            log.warn("⚠️ [MetaFlowClient] deleteFlow failed for {}: {}", metaFlowId, e.getMessage());
        }
    }

    // ── Parsing helpers ────────────────────────────────────────────────────────

    private List<FlowValidationError> parseValidationErrors(JsonNode root) {
        List<FlowValidationError> errors = new ArrayList<>();
        JsonNode arr = root.path("validation_errors");
        if (!arr.isArray()) return errors;
        for (JsonNode e : arr) {
            errors.add(new FlowValidationError(
                    e.path("error_type").asText(""),
                    e.path("error").asText(""),
                    e.path("message").asText(""),
                    e.hasNonNull("component") ? e.path("component").asText(null) : null, // optional
                    e.path("line_start").asInt(0),
                    e.path("line_end").asInt(0),
                    e.path("column_start").asInt(0),
                    e.path("column_end").asInt(0)
            ));
        }
        return errors;
    }

    private String parseMetaErrorCode(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            return root.path("error").path("code").asText(null);
        } catch (Exception ignored) { return null; }
    }

    private String parseMetaErrorMessage(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode errNode = root.path("error");
            if (!errNode.isMissingNode()) return errNode.path("message").asText(rawBody);
        } catch (Exception ignored) {}
        return rawBody;
    }
}
