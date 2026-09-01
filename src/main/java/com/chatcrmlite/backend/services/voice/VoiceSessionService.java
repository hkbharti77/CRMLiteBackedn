package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WebChatMessage;
import com.chatcrmlite.backend.models.voice.VoiceSession;
import com.chatcrmlite.backend.models.voice.VoiceTurn;
import com.chatcrmlite.backend.models.voice.VoiceUsage;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceSessionRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceTurnRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceUsageRepository;
import com.chatcrmlite.backend.services.RagRetrievalService;
import com.chatcrmlite.backend.services.WebChatService;
import com.chatcrmlite.backend.services.ai.DeepgramVoiceService;
import com.chatcrmlite.backend.services.livechat.LiveSupportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceSessionService {

    private final VoiceSessionRepository sessionRepository;
    private final VoiceTurnRepository turnRepository;
    private final VoiceUsageRepository usageRepository;
    private final UserRepository userRepository;
    private final ContactRepository contactRepository;
    private final DeepgramVoiceService deepgramVoiceService;
    private final SpeechNormalizer speechNormalizer;
    private final RagRetrievalService ragRetrievalService;
    private final WebChatService webChatService;
    private final LiveSupportService liveSupportService;

    @org.springframework.beans.factory.annotation.Value("${deepgram.tts.model:aura-asteria-en}")
    private String defaultTtsModel;

    @org.springframework.beans.factory.annotation.Value("${voice.max-audio-size-bytes:10485760}")
    private long maxAudioSizeBytes;

    // In-memory IP rate limiter (Sliding minute window)
    private final Map<String, List<Long>> ipRequestTimestamps = new ConcurrentHashMap<>();
    private static final int MAX_VOICE_REQUESTS_PER_MINUTE = 25;

    public static class VoiceTurnResult {
        public UUID sessionId;
        public int turnNumber;
        public String userTranscript;
        public String botResponseText;
        public String audioBase64;
        public String audioContentType = "audio/mp3";
        public String detectedLanguage;
        public String languageMode;
        public boolean codeSwitching;
        public boolean isHandoff;
        public boolean cancelled;
        public int sttLatencyMs;
        public int llmLatencyMs;
        public int ttsLatencyMs;
        public int ttfaMs;
        public double audioDurationSeconds;
    }

    /**
     * Rate Limiter for Client IP
     */
    public boolean checkRateLimit(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return true;
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000L;

        ipRequestTimestamps.compute(clientIp, (ip, timestamps) -> {
            if (timestamps == null) timestamps = new ArrayList<>();
            timestamps.removeIf(t -> t < windowStart);
            timestamps.add(now);
            return timestamps;
        });

        return ipRequestTimestamps.get(clientIp).size() <= MAX_VOICE_REQUESTS_PER_MINUTE;
    }

    /**
     * Process incoming audio stream or file for a business
     */
    @Transactional
    public VoiceTurnResult processVoiceTurn(
            UUID businessId,
            String visitorId,
            UUID existingSessionId,
            byte[] audioBytes,
            String clientIp,
            String clientTranscriptOverride
    ) {
        return processVoiceTurn(businessId, visitorId, existingSessionId, audioBytes, "audio/webm", "en", clientIp, clientTranscriptOverride);
    }

    /**
     * Enterprise Process Voice Turn with MIME validation, Language Preference, and Barge-In Invalidation
     */
    @Transactional
    public VoiceTurnResult processVoiceTurn(
            UUID businessId,
            String visitorId,
            UUID existingSessionId,
            byte[] audioBytes,
            String mimeType,
            String preferredLang,
            String clientIp,
            String clientTranscriptOverride
    ) {
        long overallStart = System.currentTimeMillis();

        // Ingress validation
        if (audioBytes != null && audioBytes.length > maxAudioSizeBytes) {
            throw new IllegalArgumentException("Audio payload exceeds maximum permitted size of " + (maxAudioSizeBytes / (1024 * 1024)) + "MB");
        }

        User business = userRepository.findById(businessId)
                .orElseThrow(() -> new IllegalArgumentException("Business not found for ID: " + businessId));

        // 1. Session Setup & Turn Invalidation Check
        VoiceSession session = getOrCreateSession(business, visitorId, existingSessionId);
        int turnNum = (session.getActiveTurnNumber() != null ? session.getActiveTurnNumber() : 0) + 1;
        session.setActiveTurnNumber(turnNum);
        session.setTotalTurns(turnNum);
        sessionRepository.save(session);

        // 2. Speech-to-Text (STT) via Deepgram Nova-2 (English)
        long sttStart = System.currentTimeMillis();
        String transcript = clientTranscriptOverride;
        String rawDetectedLanguage = "en";

        if (transcript == null || transcript.isBlank()) {
            if (audioBytes != null && audioBytes.length > 0) {
                DeepgramVoiceService.DeepgramTranscriptionResult sttRes =
                        deepgramVoiceService.transcribeAudio(audioBytes, mimeType, "en");
                if (sttRes.isSuccess() && !sttRes.getTranscript().isBlank()) {
                    transcript = sttRes.getTranscript();
                    rawDetectedLanguage = "en";
                }
            }
        }
        int sttLatency = (int) (System.currentTimeMillis() - sttStart);

        if (transcript == null || transcript.isBlank()) {
            transcript = "";
        }

        // 3. Language Mode & Handoff
        String languageMode = "en";
        boolean isHumanHandoffRequested = detectHumanHandoffIntent(transcript);

        // 4. Stale-Turn Check (Barge-in check before LLM)
        if (!session.isCurrentTurn(turnNum)) {
            log.info("[Barge-In] Turn #{} in session {} was invalidated by user interruption before LLM. Discarding.", turnNum, session.getId());
            VoiceTurnResult cancelledResult = new VoiceTurnResult();
            cancelledResult.sessionId = session.getId();
            cancelledResult.turnNumber = turnNum;
            cancelledResult.cancelled = true;
            return cancelledResult;
        }

        // 5. LLM & CRM Action Processing (English Spoken First)
        long llmStart = System.currentTimeMillis();
        String botReplyText;
        if (transcript.isBlank()) {
            botReplyText = "I didn't quite catch that. Could you please repeat?";
        } else if (isHumanHandoffRequested) {
            botReplyText = "Connecting you directly to a member of our support team right now. Please hold on.";
            triggerHumanHandoff(business, visitorId, session.getId().toString());
            session.setStatus(VoiceSession.VoiceSessionStatus.ESCALATED);
        } else {
            botReplyText = ragRetrievalService.getVoiceAiResponse(transcript, businessId, "en");
            if (botReplyText == null || botReplyText.isBlank()) {
                botReplyText = "Thank you for reaching out! How else can I assist you with our services today?";
            }
        }
        int llmLatency = (int) (System.currentTimeMillis() - llmStart);

        // 6. Stale-Turn Check (Barge-in check before TTS synthesis)
        if (!session.isCurrentTurn(turnNum)) {
            log.info("[Barge-In] Turn #{} in session {} was invalidated before TTS synthesis. Discarding.", turnNum, session.getId());
            VoiceTurnResult cancelledResult = new VoiceTurnResult();
            cancelledResult.sessionId = session.getId();
            cancelledResult.turnNumber = turnNum;
            cancelledResult.cancelled = true;
            return cancelledResult;
        }

        // 7. Advanced Speech Normalization
        String speakableText = speechNormalizer.normalize(botReplyText);

        // 8. Text-to-Speech (TTS) via Deepgram Aura
        long ttsStart = System.currentTimeMillis();
        String requestedVoiceId = (session.getVoiceId() != null && !session.getVoiceId().toLowerCase().contains("fish-audio"))
                ? session.getVoiceId()
                : defaultTtsModel;

        byte[] speechAudio = deepgramVoiceService.synthesizeSpeech(speakableText, requestedVoiceId);
        int ttsLatency = (int) (System.currentTimeMillis() - ttsStart);
        int ttfa = (int) (System.currentTimeMillis() - overallStart);

        // 9. Final Stale-Turn Check (Barge-in check after TTS synthesis)
        if (!session.isCurrentTurn(turnNum)) {
            log.info("[Barge-In] Turn #{} finished TTS but was invalidated by user barge-in. Discarding audio to prevent stale playback.", turnNum);
            VoiceTurnResult cancelledResult = new VoiceTurnResult();
            cancelledResult.sessionId = session.getId();
            cancelledResult.turnNumber = turnNum;
            cancelledResult.cancelled = true;
            return cancelledResult;
        }

        String audioBase64 = (speechAudio != null && speechAudio.length > 0)
                ? Base64.getEncoder().encodeToString(speechAudio)
                : "";

        // 10. Record Conversation in CRM Message log
        try {
            if (!transcript.isBlank()) {
                webChatService.saveMessage(business, visitorId, WebChatMessage.Sender.USER, "[Voice] " + transcript);
            }
            webChatService.saveMessage(business, visitorId, WebChatMessage.Sender.BOT, botReplyText);
        } catch (Exception e) {
            log.warn("Could not persist web chat message for voice turn: {}", e.getMessage());
        }

        // 11. Persist Turn and Session Telemetry
        session.setLanguage(languageMode);
        sessionRepository.save(session);

        VoiceTurn turn = new VoiceTurn();
        turn.setSession(session);
        turn.setTurnNumber(turnNum);
        turn.setUserTranscript(transcript);
        turn.setBotResponseText(botReplyText);
        turn.setAudioDurationSeconds(estimateAudioDuration(speechAudio));
        turn.setSttLatencyMs(sttLatency);
        turn.setLlmLatencyMs(llmLatency);
        turn.setTtsLatencyMs(ttsLatency);
        turn.setTtfaMs(ttfa);
        turn.setDetectedLanguage(languageMode);
        turnRepository.save(turn);

        // 12. Update Daily Usage Aggregation
        recordDailyUsage(business, 3, botReplyText.length());

        VoiceTurnResult result = new VoiceTurnResult();
        result.sessionId = session.getId();
        result.turnNumber = turnNum;
        result.userTranscript = transcript;
        result.botResponseText = botReplyText;
        result.audioBase64 = audioBase64;
        result.audioContentType = "audio/mp3";
        result.detectedLanguage = rawDetectedLanguage;
        result.languageMode = languageMode;
        result.codeSwitching = false;
        result.isHandoff = isHumanHandoffRequested;
        result.sttLatencyMs = sttLatency;
        result.llmLatencyMs = llmLatency;
        result.ttsLatencyMs = ttsLatency;
        result.ttfaMs = ttfa;
        result.audioDurationSeconds = turn.getAudioDurationSeconds();

        return result;
    }

    /**
     * Handle Barge-In (Interruption) signal from widget with turn invalidation
     */
    @Transactional
    public void handleBargeIn(UUID sessionId) {
        handleBargeIn(sessionId, null);
    }

    @Transactional
    public void handleBargeIn(UUID sessionId, Integer turnNumber) {
        if (sessionId == null) return;
        Optional<VoiceSession> sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isPresent()) {
            VoiceSession session = sessionOpt.get();
            int nextTurn = (session.getActiveTurnNumber() != null ? session.getActiveTurnNumber() : 0) + 1;
            session.setActiveTurnNumber(nextTurn);
            sessionRepository.save(session);
            log.info("[Barge-In] Session {} invalidated turn #{}. Active turn advanced to #{}.",
                    sessionId, turnNumber != null ? turnNumber : "all", nextTurn);
        }

        List<VoiceTurn> turns = turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId);
        if (!turns.isEmpty()) {
            VoiceTurn latestTurn = turns.get(turns.size() - 1);
            latestTurn.setWasInterrupted(true);
            turnRepository.save(latestTurn);
        }
    }

    private VoiceSession getOrCreateSession(User business, String visitorId, UUID sessionId) {
        if (sessionId != null) {
            Optional<VoiceSession> existing = sessionRepository.findById(sessionId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        VoiceSession session = new VoiceSession();
        session.setBusiness(business);
        session.setTenant(business.getTenant());
        session.setVisitorId(visitorId != null ? visitorId : "web_" + UUID.randomUUID());
        session.setStatus(VoiceSession.VoiceSessionStatus.ACTIVE);
        session.setVoiceId(defaultTtsModel != null && !defaultTtsModel.isBlank() ? defaultTtsModel : "deepgram/flux-tts:free");
        session.setStartedAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    private String detectLanguage(String text) {
        if (text == null || text.isBlank()) return "en";
        // Check for Devanagari script characters
        for (char c : text.toCharArray()) {
            if (c >= '\u0900' && c <= '\u097F') {
                return "hi";
            }
        }
        String lower = text.toLowerCase();
        if (lower.contains("kya") || lower.contains("hai") || lower.contains("kaise") || lower.contains("chahiye")
                || lower.contains("namaste") || lower.contains("karo") || lower.contains("haan") || lower.contains("nahi")
                || lower.contains("batao") || lower.contains("mera") || lower.contains("mujhe") || lower.contains("aap")
                || lower.contains("shukriya") || lower.contains("dhanyawad") || lower.contains("madad") || lower.contains("bataiye")) {
            return "hinglish";
        }
        return "en";
    }

    private boolean detectHumanHandoffIntent(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("human") || lower.contains("agent") || lower.contains("executive")
                || lower.contains("customer care") || lower.contains("talk to person") || lower.contains("call support");
    }

    private void triggerHumanHandoff(User business, String visitorId, String sessionId) {
        try {
            String waId = "web:voice_" + visitorId;
            Contact contact = contactRepository.findByWaIdAndOwner(waId, business)
                    .orElseGet(() -> {
                        Contact c = new Contact();
                        c.setWaId(waId);
                        c.setName("Voice Visitor");
                        c.setOwner(business);
                        c.setTenant(business.getTenant());
                        c.setSource("VOICE_ASSISTANT");
                        return contactRepository.save(c);
                    });
            liveSupportService.requestHumanSupport(contact, sessionId);
        } catch (Exception e) {
            log.error("Failed to trigger human escalation for voice: {}", e.getMessage());
        }
    }

    private void recordDailyUsage(User business, int sttSecs, int ttsChars) {
        try {
            LocalDate today = LocalDate.now();
            VoiceUsage usage = usageRepository.findByBusinessIdAndUsageDate(business.getId(), today)
                    .orElseGet(() -> {
                        VoiceUsage u = new VoiceUsage();
                        u.setBusiness(business);
                        u.setUsageDate(today);
                        u.setSttSecondsTotal(0);
                        u.setTtsCharactersTotal(0);
                        u.setRequestCount(0);
                        u.setEstimatedCostUsd(BigDecimal.ZERO);
                        return u;
                    });

            usage.setSttSecondsTotal(usage.getSttSecondsTotal() + sttSecs);
            usage.setTtsCharactersTotal(usage.getTtsCharactersTotal() + ttsChars);
            usage.setRequestCount(usage.getRequestCount() + 1);
            usageRepository.save(usage);
        } catch (Exception e) {
            log.warn("Usage tracking error: {}", e.getMessage());
        }
    }

    private double estimateAudioDuration(byte[] mp3Bytes) {
        if (mp3Bytes == null || mp3Bytes.length == 0) return 0.0;
        // Approximation: 128 kbps MP3 is ~16,000 bytes per second
        return Math.round((mp3Bytes.length / 16000.0) * 10.0) / 10.0;
    }
}
