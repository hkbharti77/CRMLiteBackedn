package com.chatcrmlite.backend.services.visitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Service for issuing and validating cryptographically signed anonymous visitor tokens.
 * Format: vid_<uuid>.<timestamp_epoch_sec>.<hmac_sha256_base64url>
 */
@Slf4j
@Service
public class VisitorTokenService {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final long DEFAULT_TOKEN_VALIDITY_SECONDS = 30L * 24 * 60 * 60; // 30 days

    @Value("${chatcrm.fair-share.visitor.token-secret:chatcrm-visitor-entropy-key-2026-prod-fallback}")
    private String tokenSecret;

    public static class VisitorIdentity {
        private final String visitorId;
        private final String fullToken;
        private final boolean isNew;

        public VisitorIdentity(String visitorId, String fullToken, boolean isNew) {
            this.visitorId = visitorId;
            this.fullToken = fullToken;
            this.isNew = isNew;
        }

        public String getVisitorId() { return visitorId; }
        public String getFullToken() { return fullToken; }
        public boolean isNew() { return isNew; }
    }

    /**
     * Validates an incoming token or generates a brand new signed token if missing/invalid/expired.
     */
    public VisitorIdentity resolveVisitorIdentity(String incomingToken, UUID tenantId) {
        if (incomingToken != null && !incomingToken.isBlank()) {
            String extractedVisitorId = extractVisitorIdIfValid(incomingToken, tenantId);
            if (extractedVisitorId != null) {
                return new VisitorIdentity(extractedVisitorId, incomingToken, false);
            }
            log.debug("⚠️ Incoming visitor token was invalid or expired for tenant {}. Re-issuing new token.", tenantId);
        }

        String newVisitorId = UUID.randomUUID().toString();
        String newToken = generateToken(newVisitorId, tenantId);
        return new VisitorIdentity(newVisitorId, newToken, true);
    }

    /**
     * Generates a signed token for a visitor ID.
     */
    public String generateToken(String visitorId, UUID tenantId) {
        long timestamp = Instant.now().getEpochSecond();
        String payload = "vid_" + visitorId + "." + timestamp + "." + (tenantId != null ? tenantId.toString() : "global");
        String signature = sign(payload);
        return "vid_" + visitorId + "." + timestamp + "." + signature;
    }

    /**
     * Validates signature and expiration; returns visitor ID if valid, null otherwise.
     */
    public String extractVisitorIdIfValid(String token, UUID tenantId) {
        if (token == null || !token.startsWith("vid_")) {
            return null;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }

        String vidWithPrefix = parts[0];
        String timestampStr = parts[1];
        String receivedSignature = parts[2];

        try {
            long tokenTimestamp = Long.parseLong(timestampStr);
            long now = Instant.now().getEpochSecond();

            // Check expiration
            if (now - tokenTimestamp > DEFAULT_TOKEN_VALIDITY_SECONDS || tokenTimestamp > (now + 300)) {
                log.debug("Visitor token expired or future timestamp: {}", tokenTimestamp);
                return null;
            }

            String visitorId = vidWithPrefix.substring(4); // Remove "vid_"
            String payload = vidWithPrefix + "." + timestampStr + "." + (tenantId != null ? tenantId.toString() : "global");
            String expectedSignature = sign(payload);

            if (MessageDigest.isEqual(
                    receivedSignature.getBytes(StandardCharsets.UTF_8),
                    expectedSignature.getBytes(StandardCharsets.UTF_8))) {
                return visitorId;
            } else {
                log.warn("🚨 Visitor token signature mismatch! Potential tampering detected.");
                return null;
            }
        } catch (Exception e) {
            log.debug("Error parsing visitor token: {}", e.getMessage());
            return null;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            SecretKeySpec secretKey = new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC signature", e);
        }
    }
}
