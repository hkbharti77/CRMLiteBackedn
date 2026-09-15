package com.chatcrmlite.backend.services.email.verifier;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class SendGridInboundVerifier implements InboundProviderVerifier {

    private final GenericInboundVerifier genericVerifier;

    @Value("${email.sendgrid.verification-key:}")
    private String verificationKey;

    @Override
    public String getProviderName() {
        return "sendgrid";
    }

    @Override
    public boolean verify(HttpServletRequest request, Map<String, String> headers, String body) {
        if (verificationKey == null || verificationKey.isBlank()) {
            // Fallback to generic shared secret verification if specific key not provided
            return genericVerifier.verify(request, headers, body);
        }

        String signature = headers.get("x-twilio-email-event-webhook-signature");
        String timestamp = headers.get("x-twilio-email-event-webhook-timestamp");

        if (signature == null || timestamp == null) {
            log.warn("[SendGridVerifier] Missing SendGrid signature headers");
            return false;
        }

        try {
            // Real SendGrid ECDSA signature verification standard check
            String payloadToVerify = timestamp + body;
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("EC");
            byte[] keyBytes = java.util.Base64.getDecoder().decode(verificationKey.trim());
            java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(keyBytes);
            java.security.PublicKey pubKey = kf.generatePublic(spec);

            java.security.Signature sig = java.security.Signature.getInstance("SHA256withECDSA");
            sig.initVerify(pubKey);
            sig.update(payloadToVerify.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return sig.verify(java.util.Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            log.error("[SendGridVerifier] Verification failed due to exception", e);
            return false;
        }
    }
}
