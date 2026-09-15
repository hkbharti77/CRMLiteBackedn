package com.chatcrmlite.backend.services.email.verifier;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
@Slf4j
public class GenericInboundVerifier implements InboundProviderVerifier {

    @Value("${email.webhook.secret:}")
    private String configuredSecret;

    @Override
    public String getProviderName() {
        return "generic";
    }

    @Override
    public boolean verify(HttpServletRequest request, Map<String, String> headers, String body) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            log.warn("[GenericInboundVerifier] Webhook secret is not configured");
            return false;
        }

        String secretHeader = headers.getOrDefault("x-webhook-secret", headers.get("x-email-webhook-secret"));
        if (secretHeader == null || secretHeader.isBlank()) {
            log.warn("[GenericInboundVerifier] Missing webhook secret header");
            return false;
        }

        return MessageDigest.isEqual(
                secretHeader.trim().getBytes(StandardCharsets.UTF_8),
                configuredSecret.trim().getBytes(StandardCharsets.UTF_8)
        );
    }
}
