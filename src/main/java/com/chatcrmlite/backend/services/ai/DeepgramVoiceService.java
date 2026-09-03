package com.chatcrmlite.backend.services.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
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
 * Enterprise-grade Direct Deepgram Voice Service for Speech-to-Text (Nova-2)
 * and Text-to-Speech (Aura).
 */
@Slf4j
@Service
public class DeepgramVoiceService {

    @Value("${deepgram.api.key:43762e9a6e2f28db37cbaea5f671582aa181a4bc}")
    private String apiKey;

    @Value("${deepgram.stt.model:nova-2}")
    private String sttModel;

    @Value("${deepgram.stt.smart-format:true}")
    private boolean smartFormat;

    @Value("${deepgram.stt.punctuate:true}")
    private boolean punctuate;

    @Value("${deepgram.stt.detect-language:true}")
    private boolean detectLanguage;

    @Value("${deepgram.tts.model:aura-stella-en}")
    private String ttsModel;

    @Value("${deepgram.connect-timeout-ms:2500}")
    private int connectTimeoutMs;

    @Value("${deepgram.read-timeout-ms:4000}")
    private int readTimeoutMs;

    @Value("${deepgram.max-retries:0}")
    private int maxRetries;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DeepgramVoiceService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(2500))
                .setReadTimeout(Duration.ofMillis(4000))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Data
    @Builder
    public static class DeepgramTranscriptionResult {
        private String transcript;
        private String detectedLanguage;
        private double confidence;
        private int latencyMs;
        private boolean success;
        private String errorMessage;
    }

    /**
     * Transcribe incoming audio bytes using Deepgram Nova-2 with code-switching and punctuation.
     *
     * @param audioBytes Raw recorded audio (WebM, WAV, MP4, etc.)
     * @param mimeType Audio MIME type (e.g. "audio/webm")
     * @param preferredLang Optional language override (e.g. "hi-IN", "en-IN")
     */
    public DeepgramTranscriptionResult transcribeAudio(byte[] audioBytes, String mimeType, String preferredLang) {
        if (audioBytes == null || audioBytes.length == 0) {
            return DeepgramTranscriptionResult.builder()
                    .transcript("")
                    .success(false)
                    .errorMessage("Empty audio bytes")
                    .build();
        }

        long start = System.currentTimeMillis();
        String safeKey = maskKey(apiKey);
        String contentType = (mimeType != null && !mimeType.isBlank()) ? mimeType : "audio/webm";

        StringBuilder urlBuilder = new StringBuilder("https://api.deepgram.com/v1/listen?model=")
                .append(sttModel)
                .append("&smart_format=").append(smartFormat)
                .append("&punctuate=").append(punctuate);

        // Deepgram Nova-2 English STT: pinned to en-IN / en for high accuracy
        String dgLang = (preferredLang != null && preferredLang.toLowerCase().startsWith("en"))
                ? preferredLang.trim()
                : "en-IN";
        urlBuilder.append("&language=").append(dgLang);

        // If the audio is raw mu-law from Exotel, we must tell Deepgram explicitly via query params
        if (contentType.toLowerCase().contains("mulaw")) {
            urlBuilder.append("&encoding=mulaw&sample_rate=8000");
        }

        String url = urlBuilder.toString();
        log.info("[Deepgram-STT] Initiating Nova-2 transcription (Bytes: {}, MIME: {}, Model: {}, Key: {})",
                audioBytes.length, contentType, sttModel, safeKey);

        int attempts = 0;
        Exception lastException = null;

        while (attempts <= maxRetries) {
            attempts++;
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Token " + apiKey.trim());
                headers.set("Content-Type", contentType);
                headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
                headers.set("User-Agent", "ChatCRMLite-DeepgramVoice/2.0");

                HttpEntity<byte[]> requestEntity = new HttpEntity<>(audioBytes, headers);
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);

                int latency = (int) (System.currentTimeMillis() - start);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    JsonNode channel = root.path("results").path("channels").path(0);
                    JsonNode alt = channel.path("alternatives").path(0);

                    String transcript = alt.path("transcript").asText("");
                    double confidence = alt.path("confidence").asDouble(0.0);
                    String detectedLang = channel.path("detected_language").asText("en");

                    log.info("[Deepgram-STT] Success in {}ms | Conf: {} | Lang: {} | Text: '{}'",
                            latency, String.format("%.2f", confidence), detectedLang,
                            transcript.length() > 60 ? transcript.substring(0, 60) + "..." : transcript);

                    return DeepgramTranscriptionResult.builder()
                            .transcript(transcript)
                            .detectedLanguage(detectedLang)
                            .confidence(confidence)
                            .latencyMs(latency)
                            .success(true)
                            .build();
                } else {
                    log.warn("[Deepgram-STT] Non-2xx response: {}", response.getStatusCode());
                    return DeepgramTranscriptionResult.builder()
                            .transcript("")
                            .success(false)
                            .errorMessage("Deepgram returned HTTP " + response.getStatusCode())
                            .build();
                }
            } catch (HttpClientErrorException e) {
                // Client errors (400, 401, 403, 413) -> Fail fast, NEVER retry
                log.error("[Deepgram-STT] Client error (HTTP {}): {}. No retry.", e.getStatusCode(), e.getResponseBodyAsString());
                return DeepgramTranscriptionResult.builder()
                        .transcript("")
                        .success(false)
                        .errorMessage("Deepgram STT client error: " + e.getStatusCode())
                        .build();
            } catch (HttpServerErrorException | ResourceAccessException e) {
                // Temporary 5xx or Connection Timeout -> Retry once if attempts <= maxRetries
                lastException = e;
                log.warn("[Deepgram-STT] Transient error on attempt {}/{}: {}", attempts, maxRetries + 1, e.getMessage());
                if (attempts <= maxRetries) {
                    try {
                        Thread.sleep(200L * attempts);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Exception e) {
                log.error("[Deepgram-STT] Unexpected exception during transcription: {}", e.getMessage(), e);
                return DeepgramTranscriptionResult.builder()
                        .transcript("")
                        .success(false)
                        .errorMessage(e.getMessage())
                        .build();
            }
        }

        int totalLatency = (int) (System.currentTimeMillis() - start);
        log.error("[Deepgram-STT] All {} attempts failed after {}ms. Last error: {}",
                attempts, totalLatency, lastException != null ? lastException.getMessage() : "unknown");

        return DeepgramTranscriptionResult.builder()
                .transcript("")
                .success(false)
                .errorMessage("STT failed after retries: " + (lastException != null ? lastException.getMessage() : "unknown"))
                .build();
    }

    /**
     * Synthesize clean text to speech audio using Deepgram Aura.
     *
     * @param spokenText Normalized speech text
     * @param customModel Optional custom Aura voice model (defaults to ttsModel)
     * @return 24kHz audio binary bytes (audio/mp3)
     */
    public byte[] synthesizeSpeech(String spokenText, String customModel) {
        if (spokenText == null || spokenText.isBlank()) {
            return new byte[0];
        }

        long start = System.currentTimeMillis();
        String activeModel = (customModel != null && !customModel.isBlank()) ? customModel : ttsModel;
        String safeKey = maskKey(apiKey);

        String url = "https://api.deepgram.com/v1/speak?model=" + activeModel + "&encoding=mulaw&sample_rate=8000&container=none";
        log.info("[Deepgram-TTS] Synthesizing speech with model={} for text length={} (Key: {})",
                activeModel, spokenText.length(), safeKey);

        int attempts = 0;
        Exception lastException = null;

        while (attempts <= maxRetries) {
            attempts++;
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Token " + apiKey.trim());
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM));
                headers.set("User-Agent", "ChatCRMLite-DeepgramVoice/2.0");

                Map<String, String> body = new HashMap<>();
                body.put("text", spokenText);

                HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(body, headers);
                ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, byte[].class);

                int latency = (int) (System.currentTimeMillis() - start);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().length > 0) {
                    log.info("[Deepgram-TTS] Success in {}ms ({} audio bytes)", latency, response.getBody().length);
                    return response.getBody();
                } else {
                    log.warn("[Deepgram-TTS] Empty response or status {}", response.getStatusCode());
                    return new byte[0];
                }
            } catch (HttpClientErrorException e) {
                // Client errors (400, 401, 403, 413) -> Fail fast, NEVER retry
                log.error("[Deepgram-TTS] Client error (HTTP {}): {}. No retry.", e.getStatusCode(), e.getResponseBodyAsString());
                return new byte[0];
            } catch (HttpServerErrorException | ResourceAccessException e) {
                lastException = e;
                log.warn("[Deepgram-TTS] Transient error on attempt {}/{}: {}", attempts, maxRetries + 1, e.getMessage());
                if (attempts <= maxRetries) {
                    try {
                        Thread.sleep(200L * attempts);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Exception e) {
                log.error("[Deepgram-TTS] Unexpected exception during speech synthesis: {}", e.getMessage(), e);
                return new byte[0];
            }
        }

        log.error("[Deepgram-TTS] All {} synthesis attempts failed. Last error: {}",
                attempts, lastException != null ? lastException.getMessage() : "unknown");
        return new byte[0];
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
