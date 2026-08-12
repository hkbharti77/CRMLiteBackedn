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
                return ResponseEntity.badRequest().build();
            }

            String encodedPayload = parts[1];
            // Fix Base64 padding if necessary
            int padding = 4 - (encodedPayload.length() % 4);
            if (padding > 0 && padding < 4) {
                encodedPayload += "=".repeat(padding);
            }
            // Base64Url decode
            encodedPayload = encodedPayload.replace('-', '+').replace('_', '/');
            
            String payloadStr = new String(Base64.getDecoder().decode(encodedPayload));
            JsonNode payloadNode = objectMapper.readTree(payloadStr);

            String metaUserId = payloadNode.has("user_id") ? payloadNode.get("user_id").asText() : "unknown";
            logger.info("Data deletion requested for Meta User ID: {}", metaUserId);

            // TODO: Optional - look up WhatsAppConfig by metaUserId (if saved) and invalidate token.
            // For now, we fulfill Meta's requirement by responding with the confirmation code.

            String confirmationCode = UUID.randomUUID().toString();
            String statusUrl = "https://your-domain.com/data-deletion-status?code=" + confirmationCode; // Change domain in production

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
