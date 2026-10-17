package com.chatcrmlite.backend.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations/meta")
public class MetaDataDeletionController {

    private static final Logger logger = LoggerFactory.getLogger(MetaDataDeletionController.class);

    @Value("${meta.app-secret:${meta.app.secret:}}")
    private String appSecret;

    @Value("${app.public.url:}")
    private String publicAppUrl;

    @Value("${platform.brand.url:https://gyanvaniai.online}")
    private String platformBrandUrl;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.chatcrmlite.backend.services.whatsapp.MetaComplianceService metaComplianceService;

    /**
     * Data Deletion Callback URL required by Meta for Facebook Login / Embedded Signup compliance.
     * Meta sends an HTTP POST request with a 'signed_request' parameter.
     */
    @PostMapping(value = "/data-deletion", consumes = org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, String>> handleDataDeletion(
            @RequestParam(name = "signed_request", required = false) String signedRequest) {
        
        logger.info("Received Meta Data Deletion callback request");

        JsonNode payloadNode = parseAndVerifySignedRequest(signedRequest);
        if (payloadNode == null) {
            return ResponseEntity.badRequest().build();
        }

        String metaUserId = payloadNode.has("user_id") ? payloadNode.get("user_id").asText() : "";
        if (metaUserId.isBlank()) {
            logger.warn("Meta signed_request missing user_id");
            return ResponseEntity.badRequest().build();
        }

        String baseUrl = (publicAppUrl != null && !publicAppUrl.isBlank()) 
                ? publicAppUrl.replaceAll("/+$", "") 
                : platformBrandUrl.replaceAll("/+$", "");

        Map<String, String> response = metaComplianceService.handleDataDeletion(metaUserId, baseUrl);
        return ResponseEntity.ok(response);
    }

    /**
     * Deauthorize Callback URL required by Meta for Facebook Login / WhatsApp Embedded Signup.
     * Triggered when a user removes or revokes access to your app from their Meta account.
     */
    @PostMapping(value = "/deauthorize", consumes = org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> handleDeauthorize(
            @RequestParam(name = "signed_request", required = false) String signedRequest) {

        logger.info("Received Meta App Deauthorization callback request");

        JsonNode payloadNode = parseAndVerifySignedRequest(signedRequest);
        if (payloadNode == null) {
            return ResponseEntity.badRequest().build();
        }

        String metaUserId = payloadNode.has("user_id") ? payloadNode.get("user_id").asText() : "";
        if (metaUserId.isBlank()) {
            logger.warn("Meta deauth signed_request missing user_id");
            return ResponseEntity.badRequest().build();
        }

        long issuedAt = payloadNode.has("issued_at") ? payloadNode.get("issued_at").asLong() : 0L;

        Map<String, Object> response = metaComplianceService.handleDeauthorization(metaUserId, issuedAt);
        return ResponseEntity.ok(response);
    }

    /**
     * Status endpoint where a user or Meta reviewer can verify their data deletion request.
     * Prevents enumeration by returning 404 for unknown codes.
     */
    @GetMapping(value = "/data-deletion/status", produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getDeletionStatus(@RequestParam(name = "code", required = false) String code) {
        if (code == null || code.isBlank()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(renderStatusHtml("Not Found", "No confirmation code provided.", "N/A", false));
        }

        String cleanCode = code.trim();
        var requestOpt = metaComplianceService.getDeletionStatus(cleanCode);
        if (requestOpt.isEmpty()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(renderStatusHtml("Not Found", "The specified deletion request code was not found or has expired.", cleanCode, false));
        }

        var deletionReq = requestOpt.get();
        String status = deletionReq.getStatus();
        String message = "COMPLETED".equalsIgnoreCase(status)
                ? "Your request to delete personal account credentials associated with this application has been successfully completed."
                : ("IN_PROGRESS".equalsIgnoreCase(status)
                ? "Your data deletion request is currently being processed by the system."
                : "Your data deletion request is pending review.");

        boolean isSuccess = "COMPLETED".equalsIgnoreCase(status);
        return ResponseEntity.ok(renderStatusHtml(status, message, cleanCode, isSuccess));
    }

    private String renderStatusHtml(String status, String message, String code, boolean isSuccess) {
        String safeCode = code != null ? code.replaceAll("[^a-zA-Z0-9\\-_]", "") : "N/A";
        String badgeColor = isSuccess ? "background:#dcfce7;color:#15803d;" : "background:#fee2e2;color:#b91c1c;";

        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Data Deletion Status | GyanVaniAi</title>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;background:#f8fafc;padding:20px;box-sizing:border-box;}" +
                ".card{background:#fff;padding:2.5rem 2rem;border-radius:16px;box-shadow:0 10px 25px rgba(0,0,0,0.06);border:1px solid #e2e8f0;max-width:480px;width:100%;text-align:center;}" +
                "h2{color:#0f172a;margin-top:0;margin-bottom:0.75rem;font-size:1.5rem;font-weight:700;}p{color:#475569;line-height:1.6;font-size:0.95rem;margin:0.75rem 0;}" +
                ".badge{display:inline-block;padding:0.4rem 1rem;border-radius:9999px;font-weight:600;font-size:0.875rem;margin:1rem 0;" + badgeColor + "}" +
                ".code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;background:#f1f5f9;color:#0f172a;padding:0.4rem 0.75rem;border-radius:6px;word-break:break-all;display:block;margin-top:0.5rem;font-size:0.85rem;border:1px solid #e2e8f0;}" +
                ".footer{margin-top:2rem;font-size:0.8rem;color:#94a3b8;border-top:1px solid #f1f5f9;padding-top:1rem;}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "<h2>Data Deletion Request</h2>" +
                "<div class='badge'>Status: " + status + "</div>" +
                "<p>" + message + "</p>" +
                "<p><strong>Confirmation Code:</strong><span class='code'>" + safeCode + "</span></p>" +
                "<div class='footer'>Official Meta Platform Compliance Gateway &bull; GyanVaniAi</div>" +
                "</div></body></html>";
    }

    /**
     * Validates and parses the HMAC-SHA256 signed_request sent by Meta.
     */
    private JsonNode parseAndVerifySignedRequest(String signedRequest) {
        if (signedRequest == null || signedRequest.isEmpty()) {
            logger.warn("Received empty signed_request from Meta");
            return null;
        }

        try {
            String[] parts = signedRequest.split("\\.");
            if (parts.length != 2) {
                logger.warn("Malformed signed_request: expected exactly two parts separated by '.'");
                return null;
            }

            if (appSecret == null || appSecret.isBlank()) {
                logger.error("Meta app secret is not configured for signed_request verification");
                return null;
            }

            String encodedSig = parts[0];
            String encodedPayload = parts[1];

            // 1. Verify HMAC-SHA256 Signature
            byte[] expectedSig = Base64.getUrlDecoder().decode(encodedSig);

            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(
                    appSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            hmac.init(secretKey);
            byte[] actualSig = hmac.doFinal(encodedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            if (!java.security.MessageDigest.isEqual(expectedSig, actualSig)) {
                logger.warn("🛑 [SECURITY] Meta signed_request signature mismatch");
                return null;
            }

            // 2. Decode and parse payload
            byte[] payloadBytes = Base64.getUrlDecoder().decode(encodedPayload);
            String payloadStr = new String(payloadBytes, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode payloadNode = objectMapper.readTree(payloadStr);

            String algorithm = payloadNode.path("algorithm").asText("");
            if (!algorithm.isBlank() && !"HMAC-SHA256".equalsIgnoreCase(algorithm)) {
                logger.warn("🛑 [SECURITY] Unsupported algorithm in Meta signed_request: {}", algorithm);
                return null;
            }

            return payloadNode;
        } catch (Exception e) {
            logger.error("Failed to parse Meta signed_request: {}", e.getMessage());
            return null;
        }
    }
}
