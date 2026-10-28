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
@Service
public class SarvamVoiceService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SarvamVoiceService.class);

    @Value("${sarvam.api.key:}")
    private String apiKey;

    @Value("${sarvam.tts.speaker:simran_enhi_customer}")
    private String defaultSpeaker;

    @Value("${sarvam.tts.model:bulbul:v4-flash}")
    private String defaultModel;

    @Value("${sarvam.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${sarvam.read-timeout-ms:10000}")
    private int readTimeoutMs;

    @Value("${sarvam.max-retries:1}")
    private int maxRetries;

    private final RestTemplate restTemplate;

    public SarvamVoiceService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(3000))
                .setReadTimeout(Duration.ofMillis(10000))
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
        String effectiveApiKey = (apiKey != null && !apiKey.isBlank())
                ? apiKey.trim()
                : com.chatcrmlite.backend.utils.DotenvLoader.get("SARVAM_API_KEY", "").trim();

        if (effectiveApiKey.isBlank()) {
            log.error("[Sarvam-TTS] Cannot synthesize speech: SARVAM_API_KEY is not configured in properties or .env.");
            return new byte[0];
        }

        long start = System.currentTimeMillis();
        String effectiveDefaultSpeaker = (defaultSpeaker != null && !defaultSpeaker.isBlank())
                ? defaultSpeaker.trim()
                : com.chatcrmlite.backend.utils.DotenvLoader.get("SARVAM_TTS_SPEAKER", "simran_enhi_customer").trim();
        String rawSpeaker = (customSpeaker != null && !customSpeaker.isBlank()) ? customSpeaker.trim() : effectiveDefaultSpeaker;

        String effectiveModel = (defaultModel != null && !defaultModel.isBlank())
                ? defaultModel.trim()
                : com.chatcrmlite.backend.utils.DotenvLoader.get("SARVAM_TTS_MODEL", "bulbul:v4-flash").trim();

        String activeSpeaker = mapToCompatibleSpeaker(rawSpeaker, effectiveModel);

        String langCode = (targetLanguageCode != null && !targetLanguageCode.isBlank()) ? targetLanguageCode.trim() : "hi-IN";
        String safeKey = maskKey(effectiveApiKey);

        String url = "https://api.sarvam.ai/text-to-speech";
        log.info("[Sarvam-TTS] Synthesizing speech with speaker={}, model={} for text length={} (Key: {})",
                activeSpeaker, effectiveModel, spokenText.length(), safeKey);

        int attempts = 0;
        Exception lastException = null;

        while (attempts <= maxRetries) {
            attempts++;
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("api-subscription-key", effectiveApiKey);
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(java.util.Arrays.asList(
                        MediaType.valueOf("audio/wav"),
                        MediaType.APPLICATION_OCTET_STREAM,
                        MediaType.APPLICATION_JSON
                ));
                headers.set("Accept-Encoding", "identity");
                headers.set("User-Agent", "ChatCRMLite-SarvamVoice/1.0");

                Map<String, Object> body = new HashMap<>();
                body.put("text", spokenText);
                body.put("inputs", Collections.singletonList(spokenText));
                body.put("target_language_code", langCode);
                body.put("speaker", activeSpeaker);
                body.put("model", effectiveModel);
                body.put("pace", 1.0);
                body.put("speech_sample_rate", 22050);

                HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
                ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, byte[].class);

                int latency = (int) (System.currentTimeMillis() - start);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    byte[] responseBytes = response.getBody();
                    MediaType contentType = response.getHeaders().getContentType();

                    boolean isAudioContentType = contentType != null && contentType.getType().equalsIgnoreCase("audio");
                    boolean isMp3Magic = responseBytes.length >= 2 && (responseBytes[0] == (byte) 0xFF && (responseBytes[1] & 0xE0) == (byte) 0xE0);
                    boolean isWavMagic = responseBytes.length >= 4 && responseBytes[0] == 'R' && responseBytes[1] == 'I' && responseBytes[2] == 'F' && responseBytes[3] == 'F';

                    if (isAudioContentType || isMp3Magic || isWavMagic) {
                        log.info("[Sarvam-TTS] Success in {}ms ({} audio bytes received)", latency, responseBytes.length);
                        return responseBytes;
                    }

                    // Fallback in case endpoint returns JSON with {"audios": ["..."]}
                    try {
                        String jsonString = new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8);
                        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonString);
                        if (root.has("audios") && root.get("audios").isArray() && root.get("audios").size() > 0) {
                            String base64Audio = root.get("audios").get(0).asText();
                            byte[] decodedWav = java.util.Base64.getDecoder().decode(base64Audio);
                            log.info("[Sarvam-TTS] Success in {}ms ({} audio bytes decoded from JSON)", latency, decodedWav.length);
                            return decodedWav;
                        } else {
                            log.warn("[Sarvam-TTS] Response was neither audio stream nor valid 'audios' JSON: {}",
                                    jsonString.substring(0, Math.min(100, jsonString.length())));
                        }
                    } catch (Exception parseEx) {
                        log.warn("[Sarvam-TTS] Failed to parse response: {}", parseEx.getMessage());
                    }
                    return new byte[0];
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

    /**
     * Normalizes and maps speaker IDs between legacy v3 names and v4-flash persona identifiers.
     */
    public static String mapToCompatibleSpeaker(String speaker, String model) {
        if (speaker == null || speaker.isBlank()) {
            return (model != null && model.contains("v4")) ? "simran_enhi_customer" : "simran";
        }
        String clean = speaker.trim().toLowerCase();

        boolean isV4 = model != null && model.contains("v4");
        if (isV4) {
            // If already in v4 persona format (e.g. simran_enhi_customer, rahul_hi_conversational)
            if (clean.contains("_")) {
                return clean;
            }
            // Map legacy v3 speaker names to their premier v4-flash personas
            switch (clean) {
                case "simran": return "simran_enhi_customer";
                case "priya": return "ishita_enhi_customer";
                case "neha": return "neha_en_customer";
                case "ishita": return "ishita_enhi_customer";
                case "ritu": return "ritu_hi_customer";
                case "sanchita": return "sanchita_hi_assistant";
                case "roopa": return "roopa_hi_conversational";
                case "shalini": return "shalini_enhi_customer";
                case "rahul": return "rahul_hi_conversational";
                case "rohan": return "rohan_en_recovery";
                case "amit": return "amit_hi_conversational";
                case "shubh": return "shubh_enhi_customer";
                case "aditya": return "aditya_hi_conversational";
                case "sunny": return "sunny_enhi_customer";
                case "ratan": return "ratan_hi_customer_expressive";
                case "dev": return "dev_en_conversational";
                default: return "simran_enhi_customer";
            }
        } else {
            // For v3 or older, extract base speaker name if persona format was passed
            if (clean.contains("_")) {
                String base = clean.split("_")[0];
                return base;
            }
            return clean;
        }
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
