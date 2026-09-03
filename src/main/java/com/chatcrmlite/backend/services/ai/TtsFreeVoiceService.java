package com.chatcrmlite.backend.services.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for Text-to-Speech using ttsfree.in APIs.
 */
@Slf4j
@Service
public class TtsFreeVoiceService {

    @Value("${ttsfree.api.key:}")
    private String apiKey;

    @Value("${ttsfree.tts.speaker:Divya}")
    private String defaultSpeaker;

    @Value("${ttsfree.tts.emotion:Happy}")
    private String defaultEmotion;

    @Value("${ttsfree.tts.language:English}")
    private String defaultLanguage;

    @Value("${ttsfree.max-retries:1}")
    private int maxRetries;

    private final RestTemplate restTemplate;

    public TtsFreeVoiceService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(3000))
                .setReadTimeout(Duration.ofMillis(6000))
                .build();
    }

    /**
     * Synthesize text to speech audio using TTSFree.
     *
     * @param spokenText Normalized speech text
     * @param customLanguage Optional language string (e.g. "Hindi", "English")
     * @return audio binary bytes
     */
    public byte[] synthesizeSpeech(String spokenText, String customLanguage) {
        if (spokenText == null || spokenText.isBlank()) {
            return new byte[0];
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.error("[TTSFree-TTS] Cannot synthesize speech: TTSFREE_API_KEY is not configured.");
            return new byte[0];
        }

        long start = System.currentTimeMillis();
        String safeKey = maskKey(apiKey);
        String url = "https://ttsfree.in/api/tts";
        
        String activeLanguage = (customLanguage != null && !customLanguage.isBlank()) ? customLanguage : defaultLanguage;
        
        log.info("[TTSFree-TTS] Synthesizing speech in language={} with speaker={}, emotion={} for text length={} (Key: {})",
                activeLanguage, defaultSpeaker, defaultEmotion, spokenText.length(), safeKey);

        int attempts = 0;
        Exception lastException = null;

        while (attempts <= maxRetries) {
            attempts++;
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Bearer " + apiKey.trim());
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM));
                headers.set("User-Agent", "ChatCRMLite-TTSFree/1.0");

                Map<String, Object> body = new HashMap<>();
                body.put("text", spokenText);
                body.put("language", activeLanguage);
                body.put("speaker", defaultSpeaker);
                body.put("emotion", defaultEmotion);

                HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
                ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, byte[].class);

                int latency = (int) (System.currentTimeMillis() - start);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().length > 0) {
                    log.info("[TTSFree-TTS] Success in {}ms ({} audio bytes)", latency, response.getBody().length);
                    return response.getBody();
                } else {
                    log.warn("[TTSFree-TTS] Empty response or status {}", response.getStatusCode());
                    return new byte[0];
                }
            } catch (HttpClientErrorException e) {
                log.error("[TTSFree-TTS] Client error (HTTP {}): {}. No retry.", e.getStatusCode(), e.getResponseBodyAsString());
                return new byte[0];
            } catch (HttpServerErrorException | ResourceAccessException e) {
                lastException = e;
                log.warn("[TTSFree-TTS] Transient error on attempt {}/{}: {}", attempts, maxRetries + 1, e.getMessage());
                if (attempts <= maxRetries) {
                    try {
                        Thread.sleep(400L * attempts);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Exception e) {
                log.error("[TTSFree-TTS] Unexpected exception during speech synthesis: {}", e.getMessage(), e);
                return new byte[0];
            }
        }

        log.error("[TTSFree-TTS] All {} synthesis attempts failed. Last error: {}",
                attempts, lastException != null ? lastException.getMessage() : "unknown");
        return new byte[0];
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}

