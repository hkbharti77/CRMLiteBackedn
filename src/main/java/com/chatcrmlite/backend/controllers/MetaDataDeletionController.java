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

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Data Deletion Callback URL required by Meta for Facebook Login/Embedded Signup.
     * Meta sends a POST request with a 'signed_request' parameter.
     */
    @PostMapping(value = "/data-deletion", consumes = org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, String>> handleDataDeletion(
            @RequestParam(name = "signed_request", required = false) String signedRequest) {
        
        logger.info("Received Meta Data Deletion request");

        if (signedRequest == null || signedRequest.isEmpty()) {
            logger.warn("Received empty signed_request from Meta");
            return ResponseEntity.badRequest().build();
        }

        try {
            // signed_request is in the format: signature.payload
            String[] parts = signedRequest.split("\\.");
            if (parts.length != 2) {
                logger.warn("Malformed signed_request: expected exactly two parts separated by '.'");
                return ResponseEntity.badRequest().build();
            }

            if (appSecret == null || appSecret.isBlank()) {
                logger.error("Meta app secret is not configured for data deletion verification");
                return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
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
                return ResponseEntity.badRequest().build();
            }

            // 2. Decode and parse payload
            byte[] payloadBytes = Base64.getUrlDecoder().decode(encodedPayload);
            String payloadStr = new String(payloadBytes, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode payloadNode = objectMapper.readTree(payloadStr);

            String algorithm = payloadNode.path("algorithm").asText("");
            if (!algorithm.isBlank() && !"HMAC-SHA256".equalsIgnoreCase(algorithm)) {
                logger.warn("🛑 [SECURITY] Unsupported algorithm in Meta signed_request: {}", algorithm);
                return ResponseEntity.badRequest().build();
            }

            String metaUserId = payloadNode.has("user_id") ? payloadNode.get("user_id").asText() : "unknown";
            logger.info("Data deletion requested for Meta User ID: {}", metaUserId);

            String confirmationCode = UUID.randomUUID().toString();
            String statusUrl = "https://your-domain.com/data-deletion-status?code=" + confirmationCode;

            Map<String, String> response = new HashMap<>();
            response.put("url", statusUrl);
            response.put("confirmation_code", confirmationCode);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to parse Meta signed_request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}
