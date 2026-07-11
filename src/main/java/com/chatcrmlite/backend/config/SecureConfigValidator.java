package com.chatcrmlite.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

@Configuration
@org.springframework.context.annotation.Profile("!test")
public class SecureConfigValidator {
    private static final Logger log = LoggerFactory.getLogger(SecureConfigValidator.class);

    @Autowired
    private Environment env;

    @PostConstruct
    public void validateConfig() {
        log.info("🛡️ Validating secure configuration...");

        List<String> missingSecrets = new ArrayList<>();

        checkSecret("jwt.secret", missingSecrets);
        String provider = env.getProperty("ai.provider", "google");
        if ("google".equalsIgnoreCase(provider)) {
            checkSecret("langchain4j.google-ai.gemini.api-key", missingSecrets);
        } else if ("openai".equalsIgnoreCase(provider) || "ollama".equalsIgnoreCase(provider) || "local".equalsIgnoreCase(provider)) {
            checkSecret("ai.openai.base-url", missingSecrets);
        }
        checkSecret("meta.app-secret", missingSecrets);
        checkSecret("whatsapp.verify-token", missingSecrets);
        checkSecret("spring.mail.password", missingSecrets);

        if (!missingSecrets.isEmpty()) {
            log.error("❌ CRITICAL: Missing required environment variables: {}", missingSecrets);
            log.error("❌ Application cannot start without these secrets. Please check your .env or environment settings.");
            System.exit(1);
        }

        log.info("✅ All critical secrets are present in environment.");
    }

    private void checkSecret(String key, List<String> missing) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank() || value.contains("${")) {
            missing.add(key);
        } else {
            log.info("✔ Found config: {} = ********", key);
        }
    }
}
