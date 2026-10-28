package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.services.voice.VoiceSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/public/voice")
@RequiredArgsConstructor
public class PublicVoiceChatController {

    private final VoiceSessionService voiceSessionService;
    private final com.chatcrmlite.backend.services.ai.DeepgramVoiceService deepgramVoiceService;
    private final com.chatcrmlite.backend.services.voice.SpeechNormalizer speechNormalizer;
    private final com.chatcrmlite.backend.repositories.UserRepository userRepository;
    private final com.chatcrmlite.backend.repositories.TenantRepository tenantRepository;
    private final com.chatcrmlite.backend.services.tenant.QuotaEnforcerService quotaEnforcerService;

    @org.springframework.beans.factory.annotation.Value("${deepgram.tts.model:aura-2-thalia-en}")
    private String defaultTtsModel;

    @org.springframework.beans.factory.annotation.Value("${deepgram.stt.model:nova-3}")
    private String defaultSttModel;

    @org.springframework.beans.factory.annotation.Value("${voice.max-tts-characters:3000}")
    private int maxTtsCharacters;

    private UUID resolveTenantId(UUID businessId) {
        if (businessId == null) return null;
        com.chatcrmlite.backend.models.User user = userRepository.findById(businessId).orElse(null);
        if (user != null) {
            return user.getTenant() != null ? user.getTenant().getId() : user.getId();
        }
        com.chatcrmlite.backend.models.Tenant tenant = tenantRepository.findById(businessId).orElse(null);
        if (tenant != null) {
            return tenant.getId();
        }
        return businessId;
    }

    /**
     * Public Voice Turn Endpoint (Multipart Audio / Fallback Transcript)
     */
    @PostMapping(value = "/{businessId}", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Map<String, Object>> handleVoiceTurn(
            @PathVariable UUID businessId,
            @RequestParam(value = "audio", required = false) MultipartFile audioFile,
            @RequestParam(value = "visitorId", required = false) String visitorId,
            @RequestParam(value = "sessionId", required = false) String sessionIdStr,
            @RequestParam(value = "transcript", required = false) String clientTranscript,
            @RequestParam(value = "preferredLang", required = false, defaultValue = "en") String preferredLang,
            HttpServletRequest request
    ) {
        UUID tenantId = resolveTenantId(businessId);
        if (!quotaEnforcerService.isVoiceBotAllowed(tenantId)) {
            log.warn("⛔ Voice Assistant turn denied for business {} (tenant {}): Plan does not permit Voice Bot.", businessId, tenantId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "success", false,
                            "error", "Voice Assistant is not included in your current subscription plan. Please upgrade to Growth, Scale, or Enterprise to enable Voice AI.",
                            "planRestricted", true
                    ));
        }

        String clientIp = getClientIp(request);
        if (!voiceSessionService.checkRateLimit(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("success", false, "error", "Rate limit exceeded. Please wait a moment."));
        }

        String requestId = "vce_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        try {
            UUID sessionId = null;
            if (sessionIdStr != null && !sessionIdStr.isBlank() && !sessionIdStr.equals("null")) {
                try {
                    sessionId = UUID.fromString(sessionIdStr);
                } catch (IllegalArgumentException ignored) {}
            }

            byte[] audioBytes = (audioFile != null && !audioFile.isEmpty()) ? audioFile.getBytes() : null;
            String mimeType = (audioFile != null && audioFile.getContentType() != null)
                    ? audioFile.getContentType()
                    : "audio/webm";

            VoiceSessionService.VoiceTurnResult result = voiceSessionService.processVoiceTurn(
                    businessId,
                    visitorId,
                    sessionId,
                    audioBytes,
                    mimeType,
                    preferredLang,
                    clientIp,
                    clientTranscript
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", !result.cancelled);
            response.put("requestId", requestId);
            response.put("sessionId", result.sessionId != null ? result.sessionId.toString() : "");
            response.put("turnNumber", result.turnNumber);
            response.put("cancelled", result.cancelled);
            response.put("userTranscript", result.userTranscript);
            response.put("botResponseText", result.botResponseText);
            response.put("audioBase64", result.audioBase64);
            response.put("audioContentType", result.audioContentType);
            response.put("detectedLanguage", result.detectedLanguage);
            response.put("languageMode", result.languageMode);
            response.put("codeSwitching", result.codeSwitching);
            response.put("isHandoff", result.isHandoff);
            response.put("audioDurationSeconds", result.audioDurationSeconds);

            Map<String, Object> telemetry = new HashMap<>();
            telemetry.put("sttLatencyMs", result.sttLatencyMs);
            telemetry.put("llmLatencyMs", result.llmLatencyMs);
            telemetry.put("ttsLatencyMs", result.ttsLatencyMs);
            telemetry.put("totalLatencyMs", result.ttfaMs);
            response.put("telemetry", telemetry);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Voice-Request-Id", requestId);
            headers.set("X-Voice-Total-Latency-Ms", String.valueOf(result.ttfaMs));
            headers.set("X-Voice-STT-Latency-Ms", String.valueOf(result.sttLatencyMs));
            headers.set("X-Voice-TTS-Latency-Ms", String.valueOf(result.ttsLatencyMs));

            return new ResponseEntity<>(response, headers, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            log.warn("Validation error processing voice turn for business {}: {}", businessId, e.getMessage());
            Map<String, Object> fallbackRes = new HashMap<>();
            fallbackRes.put("success", true);
            fallbackRes.put("requestId", requestId);
            fallbackRes.put("sessionId", sessionIdStr != null ? sessionIdStr : "");
            fallbackRes.put("turnNumber", 1);
            fallbackRes.put("cancelled", false);
            fallbackRes.put("userTranscript", clientTranscript != null ? clientTranscript : "");
            fallbackRes.put("botResponseText", "Thank you for reaching out! How can I assist you with our services today?");
            return ResponseEntity.ok(fallbackRes);
        } catch (Exception e) {
            log.error("Error processing voice turn for business {}: {}", businessId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Voice processing failed: " + e.getMessage()));
        }
    }

    /**
     * Direct Text-To-Speech (TTS) Endpoint with character limit & SpeechNormalizer
     */
    @PostMapping("/{businessId}/tts")
    public ResponseEntity<byte[]> synthesizeTextToSpeech(
            @PathVariable UUID businessId,
            @RequestBody Map<String, String> payload,
            HttpServletRequest request
    ) {
        UUID tenantId = resolveTenantId(businessId);
        if (!quotaEnforcerService.isVoiceBotAllowed(tenantId)) {
            log.warn("⛔ TTS synthesis denied for business {} (tenant {}): Voice Bot not permitted in subscription plan.", businessId, tenantId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String clientIp = getClientIp(request);
        if (!voiceSessionService.checkRateLimit(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        String rawText = payload.get("text");
        String requestedVoice = payload.get("voiceModel");
        String voiceModel = (requestedVoice != null && !requestedVoice.isBlank() && !requestedVoice.toLowerCase().contains("fish-audio"))
                ? requestedVoice
                : defaultTtsModel;

        if (rawText == null || rawText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        if (rawText.length() > maxTtsCharacters) {
            log.warn("TTS request text exceeded maximum character limit ({} > {})", rawText.length(), maxTtsCharacters);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }

        String speakableText = speechNormalizer.normalize(rawText);
        byte[] audioMp3 = deepgramVoiceService.synthesizeSpeech(speakableText, voiceModel);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("audio/mpeg"));
        headers.setContentLength(audioMp3.length);
        headers.set("Cache-Control", "public, max-age=86400");

        return new ResponseEntity<>(audioMp3, headers, HttpStatus.OK);
    }

    /**
     * Barge-In Notification Endpoint with Turn Invalidation
     */
    @PostMapping("/{businessId}/barge-in")
    public ResponseEntity<Map<String, Object>> reportBargeIn(
            @PathVariable UUID businessId,
            @RequestBody Map<String, Object> payload
    ) {
        String sessionIdStr = (String) payload.get("sessionId");
        Integer turnNumber = payload.get("turnNumber") instanceof Number
                ? ((Number) payload.get("turnNumber")).intValue()
                : null;

        if (sessionIdStr != null && !sessionIdStr.isBlank()) {
            try {
                voiceSessionService.handleBargeIn(UUID.fromString(sessionIdStr), turnNumber);
            } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(Map.of("status", "BARGE_IN_ACKNOWLEDGED", "turnNumber", turnNumber != null ? turnNumber : 0));
    }

    /**
     * Voice Assistant Configuration & Capabilities
     */
    @GetMapping("/config/{businessId}")
    public ResponseEntity<Map<String, Object>> getVoiceConfig(@PathVariable UUID businessId) {
        UUID tenantId = resolveTenantId(businessId);
        boolean voiceAllowed = quotaEnforcerService.isVoiceBotAllowed(tenantId);

        Map<String, Object> config = new HashMap<>();
        config.put("voiceEnabled", voiceAllowed);
        config.put("defaultModelTTS", defaultTtsModel);
        config.put("defaultModelSTT", defaultSttModel);
        config.put("silenceTimeoutMs", 800);
        config.put("maxTurnDurationSeconds", 45);
        config.put("supportedLanguages", new String[]{"en", "hi", "hinglish"});
        config.put("autoPlayVoice", voiceAllowed);
        return ResponseEntity.ok(config);
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isBlank()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
}
