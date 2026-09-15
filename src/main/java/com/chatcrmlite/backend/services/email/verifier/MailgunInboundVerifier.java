package com.chatcrmlite.backend.services.email.verifier;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class MailgunInboundVerifier implements InboundProviderVerifier {

    private final GenericInboundVerifier genericVerifier;

    @Value("${email.mailgun.signing-key:}")
    private String signingKey;

    @Override
    public String getProviderName() {
        return "mailgun";
    }

    @Override
    public boolean verify(HttpServletRequest request, Map<String, String> headers, String body) {
        if (signingKey == null || signingKey.isBlank()) {
            return genericVerifier.verify(request, headers, body);
        }

        String timestamp = request.getParameter("timestamp");
        String token = request.getParameter("token");
        String signature = request.getParameter("signature");

        if (timestamp == null || token == null || signature == null) {
            log.warn("[MailgunVerifier] Missing Mailgun signature parameters");
            return false;
        }

        try {
            String data = timestamp + token;
            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            hmac.init(secretKey);
            byte[] hmacBytes = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder sb = new StringBuilder();
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b));
            }
            String calculatedSignature = sb.toString();

            return MessageDigest.isEqual(
                    calculatedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("[MailgunVerifier] Verification failed due to exception", e);
            return false;
        }
    }
}
