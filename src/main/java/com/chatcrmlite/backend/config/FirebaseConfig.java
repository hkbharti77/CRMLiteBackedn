package com.chatcrmlite.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${firebase.credentials.path:}")
    private String credentialsPath;

    @Value("${firebase.credentials.json:}")
    private String credentialsJson;

    @Bean
    public FirebaseApp firebaseApp() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }

        try {
            InputStream serviceAccount = null;

            if (credentialsJson != null && !credentialsJson.isBlank()) {
                serviceAccount = new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8));
                log.info("[FirebaseConfig] Initializing Firebase with inline JSON credentials");
            } else if (credentialsPath != null && !credentialsPath.isBlank()) {
                serviceAccount = new FileInputStream(credentialsPath);
                log.info("[FirebaseConfig] Initializing Firebase with credentials from path: {}", credentialsPath);
            } else if (new java.io.File("serviceAccountKey.json").exists()) {
                serviceAccount = new FileInputStream("serviceAccountKey.json");
                log.info("[FirebaseConfig] Found and initialized serviceAccountKey.json from working directory");
            } else {
                InputStream cpStream = getClass().getClassLoader().getResourceAsStream("serviceAccountKey.json");
                if (cpStream != null) {
                    serviceAccount = cpStream;
                    log.info("[FirebaseConfig] Found and initialized serviceAccountKey.json from classpath");
                }
            }

            FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder();
            if (serviceAccount != null) {
                optionsBuilder.setCredentials(GoogleCredentials.fromStream(serviceAccount));
            } else {
                try {
                    optionsBuilder.setCredentials(GoogleCredentials.getApplicationDefault());
                    log.info("[FirebaseConfig] Initializing Firebase with Google Application Default Credentials");
                } catch (Exception ex) {
                    log.warn("[FirebaseConfig] No Firebase credentials provided. FCM will operate in dry-run/mock mode.");
                    return null;
                }
            }

            return FirebaseApp.initializeApp(optionsBuilder.build());
        } catch (Exception e) {
            log.warn("[FirebaseConfig] Failed to initialize FirebaseApp: {}. FCM will operate in mock mode.", e.getMessage());
            return null;
        }
    }
}
