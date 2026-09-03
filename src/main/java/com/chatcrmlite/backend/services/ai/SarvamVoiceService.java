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
 * Service for Text-to-Speech using Sarvam AI (samvaad) APIs.
 */
@Slf4j
@Service
public class SarvamVoiceService {

    @Value("${sarvam.api.key:}")
    private String apiKey;

    @Value("${sarvam.tts.speaker:simran}")
    private String defaultSpeaker;

    @Value("${sarvam.tts.model:bulbul:v3}")
    private String defaultModel;

    @Value("${sarvam.connect-timeout-ms:2500}")
    private int connectTimeoutMs;

    @Value("${sarvam.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Value("${sarvam.max-retries:1}")
    private int maxRetries;

    private final RestTemplate restTemplate;

    public SarvamVoiceService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(2500))
                .setReadTimeout(Duration.ofMillis(5000))
                .build();
    }

    /**
     * Synthesize text to speech audio using Sarvam AI.
     *
     * @param spokenText Normalized speech text
     * @param targetLanguageCode Target language, e.g., "hi-IN" or "en-IN"
     * @param customSpeaker Optional custom speaker (defaults to defaultSpeaker)
     * @return audio binary bytes
     */
    public byte[] synthesizeSpeech(String spokenText, String targetLanguageCode, String customSpeaker) {
        if (spokenText == null || spokenText.isBlank()) {
            return new byte[0];
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.error("[Sarvam-TTS] Cannot synthesize speech: SARVAM_API_KEY is not configured.");
            return new byte[0];
        }

        long start = System.currentTimeMillis();
        String activeSpeaker = (customSpeaker != null && !customSpeaker.isBlank()) ? customSpeaker : defaultSpeaker;
        String langCode = (targetLanguageCode != null && !targetLanguageCode.isBlank()) ? targetLanguageCode : "hi-IN";
        String safeKey = maskKey(apiKey);

        String url = "https://api.sarvam.ai/text-to-speech/stream";
        log.info("[Sarvam-TTS] Synthesizing speech with speaker={}, model={} for text length={} (Key: {})",
                activeSpeaker, defaultModel, spokenText.length(), safeKey);

        int attempts = 0;
        Exception lastException = null;

        while (attempts <= maxRetries) {
            attempts++;
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("api-subscription-key", apiKey.trim());
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM));
                headers.set("User-Agent", "ChatCRMLite-SarvamVoice/1.0");

                Map<String, Object> body = new HashMap<>();
                body.put("text", spokenText);
                body.put("target_language_code", langCode);
                body.put("speaker", activeSpeaker);
                body.put("model", defaultModel);
                body.put("pace", 1.0);
                body.put("speech_sample_rate", 22050);

                HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
                ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, byte[].class);

                int latency = (int) (System.currentTimeMillis() - start);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().length > 0) {
                    log.info("[Sarvam-TTS] Success in {}ms ({} audio bytes)", latency, response.getBody().length);
                    return response.getBody();
                } else {
                    log.warn("[Sarvam-TTS] Empty response or status {}", response.getStatusCode());
                    return new byte[0];
                }
            } catch (HttpClientErrorException e) {
                // Client errors (400, 401, 403, 413) -> Fail fast, NEVER retry
                log.error("[Sarvam-TTS] Client error (HTTP {}): {}. No retry.", e.getStatusCode(), e.getResponseBodyAsString());
                return new byte[0];
            } catch (HttpServerErrorException | ResourceAccessException e) {
                lastException = e;
                log.warn("[Sarvam-TTS] Transient error on attempt {}/{}: {}", attempts, maxRetries + 1, e.getMessage());
                if (attempts <= maxRetries) {
                    try {
                        Thread.sleep(300L * attempts);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Exception e) {
                log.error("[Sarvam-TTS] Unexpected exception during speech synthesis: {}", e.getMessage(), e);
                return new byte[0];
            }
        }

        log.error("[Sarvam-TTS] All {} synthesis attempts failed. Last error: {}",
                attempts, lastException != null ? lastException.getMessage() : "unknown");
        return new byte[0];
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
