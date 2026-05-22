package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to verify Meta WhatsApp Webhook signatures.
 * Prevents spoofing attacks by ensuring payloads are cryptographically signed by Meta.
 */
@Slf4j
@Service
public class WebhookSignatureService {

    @Autowired
    private WhatsAppConfigRepository whatsAppConfigRepository;

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("\"timestamp\"\\s*:\\s*\"?(\\d+)\"?");
    private static final long MAX_SKEW_SECONDS = 300; // 5 minutes

    /**
     * Verifies the signature of the incoming webhook payload.
     * Gets the app secret from the database based on the phone number ID in the payload.
     * 
     * @param payload The raw string payload from the request body.
     * @param signatureHeader The value of the X-Hub-Signature-256 header.
     * @return true if the signature is valid, false otherwise.
     */
    public boolean verifySignature(String payload, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            log.warn("Invalid signature header format or missing header.");
            return false;
        }

        // Extract phone number ID from payload to find the correct WhatsApp config
        String phoneNumberId = extractPhoneNumberIdFromPayload(payload);
        if (phoneNumberId == null) {
            log.warn("Could not extract phone_number_id from payload for signature verification");
            return false;
        }

        // Get the app secret from database
        WhatsAppConfig config = whatsAppConfigRepository.findByPhoneNumberId(phoneNumberId).orElse(null);
        if (config == null) {
            log.warn("No WhatsApp config found for phone_number_id: {}", phoneNumberId);
            return false;
        }

        String appSecret = config.getAppSecret();
        if (appSecret == null || appSecret.isBlank()) {
            log.error("CRITICAL: App secret is not configured for phone_number_id: {}. Webhook verification will fail.", phoneNumberId);
            return false;
        }

        try {
            String receivedSignature = signatureHeader.substring(7); // Remove "sha256="
            
            Mac sha256HMAC = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKey = new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            sha256HMAC.init(secretKey);

            byte[] hashBytes = sha256HMAC.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = HexFormat.of().formatHex(hashBytes);

            // Constant-time comparison to prevent timing attacks
            boolean isSignatureValid = MessageDigest.isEqual(
                    receivedSignature.toLowerCase().getBytes(StandardCharsets.UTF_8),
                    expectedSignature.toLowerCase().getBytes(StandardCharsets.UTF_8)
            );

            if (!isSignatureValid) {
                log.warn("Signature mismatch. Received: {}, Expected: [REDACTED]", signatureHeader);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("Error during HMAC SHA-256 verification", e);
            return false;
        }
    }

    /**
     * Validates that the payload contains a recent timestamp to prevent replay attacks.
     * Note: Meta WhatsApp payloads include timestamps in the JSON.
     * 
     * @param payload The raw payload string.
     * @return true if the timestamp is within the allowed skew, false otherwise.
     */
    public boolean isTimestampValid(String payload) {
        Matcher matcher = TIMESTAMP_PATTERN.matcher(payload);
        
        if (matcher.find()) {
            try {
                long payloadTimestamp = Long.parseLong(matcher.group(1));
                long currentTimestamp = System.currentTimeMillis() / 1000;
                long diff = Math.abs(currentTimestamp - payloadTimestamp);
                
                if (diff > MAX_SKEW_SECONDS) {
                    log.warn("Replay Protection: Webhook timestamp is too old or too far in the future. Diff: {}s", diff);
                    return false;
                }
                return true;
            } catch (NumberFormatException e) {
                log.error("Failed to parse timestamp from payload: {}", matcher.group(1));
            }
        }
        
        // If no timestamp found, we allow it but log a debug message.
        // Some Meta payloads (like account updates) might have different structures.
        log.debug("No timestamp found in payload for replay protection check.");
        return true; 
    }

    /**
     * Extracts the phone_number_id from the WhatsApp webhook payload.
     * This is used to identify which WhatsApp config (and app secret) to use for verification.
     * 
     * @param payload The raw JSON payload from WhatsApp
     * @return The phone_number_id if found, null otherwise
     */
    private String extractPhoneNumberIdFromPayload(String payload) {
        try {
            // WhatsApp webhook payload structure:
            // {"entry":[{"id":"WABA_ID","changes":[{"value":{"metadata":{"phone_number_id":"PHONE_NUMBER_ID"}}}]}]}
            Pattern phoneNumberIdPattern = Pattern.compile("\"phone_number_id\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = phoneNumberIdPattern.matcher(payload);
            
            if (matcher.find()) {
                return matcher.group(1);
            }
            
            log.warn("Could not find phone_number_id in webhook payload");
            return null;
        } catch (Exception e) {
            log.error("Error extracting phone_number_id from payload", e);
            return null;
        }
    }
}
