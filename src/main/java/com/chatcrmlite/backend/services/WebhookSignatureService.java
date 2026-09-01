package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${meta.app-secret:${META_APP_SECRET:}}")
    private String globalAppSecret;

    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * Verifies the signature of the incoming webhook payload.
     * Computes HMAC-SHA256 of the exact raw payload using the resolved app secret
     * and performs constant-time comparison against the X-Hub-Signature-256 header.
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

        if (payload == null) {
            log.warn("Webhook payload is null.");
            return false;
        }

        String appSecret = resolveAppSecret(payload);
        if (appSecret == null || appSecret.isBlank()) {
            log.error("CRITICAL: Meta App secret is not configured. Webhook verification cannot proceed.");
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
     * Resolves the Meta App Secret to use for HMAC verification.
     * First attempts to look up the tenant WhatsAppConfig by phone_number_id.
     * If not found or if the config has no secret, falls back to the globally configured meta.app-secret.
     *
     * @param payload The raw webhook payload
     * @return The app secret to verify against, or null if unconfigured
     */
    private String resolveAppSecret(String payload) {
        String phoneNumberId = extractPhoneNumberIdFromPayload(payload);
        if (phoneNumberId != null && !phoneNumberId.isBlank()) {
            try {
                WhatsAppConfig config = whatsAppConfigRepository.findByPhoneNumberId(phoneNumberId).orElse(null);
                if (config != null && config.getAppSecret() != null && !config.getAppSecret().isBlank()) {
                    return config.getAppSecret();
                }
            } catch (Exception e) {
                log.warn("Error looking up WhatsAppConfig for phone_number_id: {}. Falling back to global secret.", phoneNumberId, e);
            }
        }
        return (globalAppSecret != null && !globalAppSecret.isBlank()) ? globalAppSecret : null;
    }

    /**
     * @deprecated Payload timestamp validation has been removed from the WhatsApp webhook authentication path.
     * Meta WhatsApp payloads contain internal message/status event timestamps (which can be hours or days old
     * during webhook retries or delayed deliveries), not HTTP transport replay timestamps.
     * Always returns true for backward compatibility.
     *
     * @param payload The raw payload string.
     * @return true always.
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public boolean isTimestampValid(String payload) {
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
