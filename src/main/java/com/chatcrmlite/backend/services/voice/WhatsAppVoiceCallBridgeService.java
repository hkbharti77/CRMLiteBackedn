package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.services.ai.DeepgramVoiceService;
import com.chatcrmlite.backend.services.voice.tools.ToolExecutionContext;

import com.chatcrmlite.backend.models.voice.VoiceSession;
import com.chatcrmlite.backend.models.voice.VoiceTurn;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.voice.VoiceSessionRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceTurnRepository;
import com.chatcrmlite.backend.repositories.UserRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import com.chatcrmlite.backend.services.voice.state.CallSessionContext;
import com.chatcrmlite.backend.services.voice.state.CallState;
import com.chatcrmlite.backend.services.voice.state.TurnContext;

@Slf4j
@Service
public class WhatsAppVoiceCallBridgeService {

    private final VoiceSessionService voiceSessionService;
    private final ConversationOrchestrator conversationOrchestrator;
    private final VoiceHandoffService voiceHandoffService;
    private final WhatsAppCallSessionRepository callSessionRepository;
    private final DeepgramVoiceService deepgramVoiceService;
    private final com.chatcrmlite.backend.services.ai.SarvamVoiceService sarvamVoiceService;
    private final com.chatcrmlite.backend.repositories.voice.VoiceAssistantConfigRepository voiceConfigRepository;
    private final VoiceSessionRepository voiceSessionRepository;
    private final VoiceTurnRepository voiceTurnRepository;
    private final UserRepository userRepository;

    // Call-scoped conversation memory
    private final ConcurrentHashMap<String, List<ChatMessage>> callHistories = new ConcurrentHashMap<>();
    
    // Call-scoped state machine
    private final ConcurrentHashMap<String, CallSessionContext> activeCalls = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public WhatsAppVoiceCallBridgeService(
            VoiceSessionService voiceSessionService,
            ConversationOrchestrator conversationOrchestrator,
            VoiceHandoffService voiceHandoffService,
            WhatsAppCallSessionRepository callSessionRepository,
            DeepgramVoiceService deepgramVoiceService,
            com.chatcrmlite.backend.services.ai.SarvamVoiceService sarvamVoiceService,
            com.chatcrmlite.backend.repositories.voice.VoiceAssistantConfigRepository voiceConfigRepository,
            VoiceSessionRepository voiceSessionRepository,
            VoiceTurnRepository voiceTurnRepository,
            UserRepository userRepository
    ) {
        this.voiceSessionService = voiceSessionService;
        this.conversationOrchestrator = conversationOrchestrator;
        this.voiceHandoffService = voiceHandoffService;
        this.callSessionRepository = callSessionRepository;
        this.deepgramVoiceService = deepgramVoiceService;
        this.sarvamVoiceService = sarvamVoiceService;
        this.voiceConfigRepository = voiceConfigRepository;
        this.voiceSessionRepository = voiceSessionRepository;
        this.voiceTurnRepository = voiceTurnRepository;
        this.userRepository = userRepository;
    }

    public WhatsAppVoiceCallBridgeService(
            VoiceSessionService voiceSessionService,
            ConversationOrchestrator conversationOrchestrator,
            VoiceHandoffService voiceHandoffService,
            WhatsAppCallSessionRepository callSessionRepository,
            DeepgramVoiceService deepgramVoiceService,
            com.chatcrmlite.backend.services.ai.SarvamVoiceService sarvamVoiceService,
            com.chatcrmlite.backend.repositories.voice.VoiceAssistantConfigRepository voiceConfigRepository
    ) {
        this(voiceSessionService, conversationOrchestrator, voiceHandoffService, callSessionRepository,
                deepgramVoiceService, sarvamVoiceService, voiceConfigRepository, null, null, null);
    }

    /**
     * Processes a voice turn during a WhatsApp Call Session (Speech-to-Text -> LLM Tool Orchestration -> Text-to-Speech)
     */
    public WhatsAppVoiceTurnResult processCallTurn(UUID tenantId, String callId, byte[] pcmAudioBytes, String mimeType, String clientTranscriptOverride, java.util.function.Consumer<byte[]> ttsChunkCallback) {
        log.info("🎙️ [WhatsAppVoiceBridge] Processing voice turn for callId={} audioBytes={}", callId, pcmAudioBytes != null ? pcmAudioBytes.length : 0);

        WhatsAppCallSession callSession = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (callSession == null) {
            log.warn("⚠️ [WhatsAppVoiceBridge] Call session not found for callId={}", callId);
            return new WhatsAppVoiceTurnResult(callId, "Call session not found", null, false, new byte[0]);
        }

        // 0. Load Tenant-Specific AI Voice Assistant Configuration
        var voiceConfigOpt = voiceConfigRepository.findByTenantId(tenantId);
        String assistantName = voiceConfigOpt.map(c -> c.getAssistantName()).filter(s -> !s.isBlank()).orElse("AI Assistant");
        String personaPrompt = voiceConfigOpt.map(c -> c.getPersonaPrompt()).filter(s -> !s.isBlank()).orElse(
                "You are " + assistantName + ", a helpful, polite and professional AI voice assistant for WhatsApp Business calls. " +
                "Keep answers brief, conversational, and natural for speech. Do not use markdown bullet points or special formatting in spoken output."
        );
        String ttsVoiceId = voiceConfigOpt.map(c -> c.getTtsVoiceId()).filter(s -> !s.isBlank()).orElse(null);

        CallSessionContext callContext = activeCalls.computeIfAbsent(callId, k -> new CallSessionContext(callId));
        callContext.transitionTo(CallState.TRANSCRIBING);

        long turnStartMs = System.currentTimeMillis();
        long sttEndMs = turnStartMs;
        // 1. Speech-to-Text (STT) if audio bytes provided
        String userTranscript = clientTranscriptOverride;
        double sttConfidence = 0.0;
        if ((userTranscript == null || userTranscript.isBlank()) && pcmAudioBytes != null && pcmAudioBytes.length > 0) {
            try {
                DeepgramVoiceService.DeepgramTranscriptionResult sttResult =
                        deepgramVoiceService.transcribeAudio(pcmAudioBytes, mimeType != null ? mimeType : "audio/wav", "en");
                sttEndMs = System.currentTimeMillis();
                log.info("📈 [LATENCY-METRIC] Metric A: User stopped speaking -> STT result: {}ms", (sttEndMs - turnStartMs));
                if (sttResult != null && sttResult.getTranscript() != null) {
                    userTranscript = sttResult.getTranscript();
                    sttConfidence = sttResult.getConfidence();
                }
            } catch (Exception e) {
                log.error("❌ [WhatsAppVoiceBridge] STT error for callId={}: {}", callId, e.getMessage());
            }
        }

        // 1b. HARD STT VALIDATION GATE
        boolean isOverride = (clientTranscriptOverride != null && !clientTranscriptOverride.isBlank());
        if (!isOverride && (userTranscript == null || userTranscript.trim().isEmpty() || sttConfidence < 0.2)) {
            log.warn("🚨 [STT-GATE] rejected empty/low-confidence transcript callId={} confidence={}", callId, sttConfidence);
            callContext.transitionTo(CallState.LISTENING);
            return new WhatsAppVoiceTurnResult(callId, "", "", false, new byte[0]);
        }

        // Start a new turn, cancelling any previous active turn
        TurnContext turnContext = callContext.startNewTurn();
        final int currentTurnId = turnContext.getTurnId();
        callContext.transitionTo(CallState.PROCESSING);
        
        log.info("▶️ [WhatsAppVoiceBridge] Starting Turn {} for callId={} [transcriptChars={}]", currentTurnId, callId, userTranscript.length());
        log.debug("▶️ [WhatsAppVoiceBridge] Turn {} transcript (debug): '{}'", currentTurnId, userTranscript);

        // 2. Check Voice Safety & Human Handoff Guardrails
        boolean handoffNeeded = voiceHandoffService.evaluateHandoffRequired(userTranscript, null, 1);
        if (handoffNeeded) {
            voiceHandoffService.executeHandoff(tenantId, callId, "USER_REQUESTED_HUMAN");
            return new WhatsAppVoiceTurnResult(callId, "I am connecting you with a human customer support agent right now. Please stay on the line.", null, true, new byte[0]);
        }
        
        // Ensure turn hasn't been cancelled by barge-in/termination before LLM
        try {
            turnContext.verifyActive();
        } catch (CancellationException e) {
            log.info("⏹️ [WhatsAppVoiceBridge] Turn {} cancelled before LLM for callId={}", currentTurnId, callId);
            return new WhatsAppVoiceTurnResult(callId, "", userTranscript, false, new byte[0]);
        }

        // 3. LLM Orchestration with Dynamic Tool Execution using Tenant's Persona
        ToolExecutionContext toolContext = new ToolExecutionContext(tenantId, null, callSession.getId(), null, callId, null, callSession.getFromWaId());
        
        List<ChatMessage> history = callHistories.computeIfAbsent(callId, k -> new ArrayList<>());
        
        UserMessage userMsg = UserMessage.from(userTranscript);
        history.add(userMsg);
        
        long llmStartMs = System.currentTimeMillis();
        long llmEndMs = llmStartMs;
        String aiAnswer = null;
        try {
            turnContext.verifyActive();
            aiAnswer = conversationOrchestrator.executeTurn(personaPrompt, userTranscript, new ArrayList<>(history.subList(0, history.size() - 1)), toolContext);
            llmEndMs = System.currentTimeMillis();
            log.info("📈 [LATENCY-METRIC] Metric B: STT result -> LLM final response: {}ms", (llmEndMs - llmStartMs));
        } catch (CancellationException e) {
            log.info("⏹️ [WhatsAppVoiceBridge] Turn {} cancelled before/during LLM for callId={}", currentTurnId, callId);
            return new WhatsAppVoiceTurnResult(callId, "", userTranscript, false, new byte[0]);
        } catch (Exception e) {
            log.error("❌ [WhatsAppVoiceBridge] LLM execution error for callId={}: {}", callId, e.getMessage());
            boolean isWelcomeGreeting = "Hello, I just connected".equalsIgnoreCase(clientTranscriptOverride);
            aiAnswer = isWelcomeGreeting
                    ? "I can hear you clearly. How can I assist you today?"
                    : "I am having trouble processing that right now. Please repeat or ask something else.";
        }

        // If empty response, provide a safe friendly voice reply so caller never hears silence
        if (aiAnswer == null || aiAnswer.trim().isEmpty()) {
             log.warn("⚠️ [WhatsAppVoiceBridge] LLM returned empty response for callId={} turnId={}", callId, currentTurnId);
             aiAnswer = "Hello, I am listening. How can I help you today?";
        }

        // Ensure turn hasn't been cancelled before TTS
        try {
            turnContext.verifyActive();
        } catch (CancellationException e) {
            log.info("⏹️ [WhatsAppVoiceBridge] Turn {} cancelled before TTS for callId={}", currentTurnId, callId);
            return new WhatsAppVoiceTurnResult(callId, "", userTranscript, false, new byte[0]);
        }

        history.add(AiMessage.from(aiAnswer));

        log.info("🤖 [WhatsAppVoiceBridge] Tenant [{}] AI Answer generated for callId={} turnId={} [answerChars={}]", tenantId, callId, currentTurnId, aiAnswer.length());
        log.debug("🤖 [WhatsAppVoiceBridge] Turn {} AI Answer (debug): {}", currentTurnId, aiAnswer);

        // 4. Synthesize TTS Audio using Tenant's configured Voice
        callContext.transitionTo(CallState.SPEAKING);
        final AtomicLong ttsFirstChunkMs = new AtomicLong(0);
        long ttsStartMs = System.currentTimeMillis();
        try {
            if (ttsChunkCallback != null) {
                // Execute sentence-chunked streaming TTS
                final java.util.concurrent.atomic.AtomicBoolean firstChunkLogged = new java.util.concurrent.atomic.AtomicBoolean(false);
                
                String voiceCleanedAnswer = SentenceChunker.cleanForVoice(aiAnswer);
                // For typical voice turns (<= 250 chars), stream as a single continuous speech unit to prevent inter-sentence dead pauses.
                List<String> units;
                if (voiceCleanedAnswer.length() <= 250) {
                    units = List.of(voiceCleanedAnswer);
                } else {
                    units = SentenceChunker.chunkText(voiceCleanedAnswer);
                    if (units.isEmpty()) {
                        units = List.of(voiceCleanedAnswer);
                    }
                }

                log.info("🗣️ [WhatsAppVoiceBridge] Pipeline streaming {} audio unit(s) to TTS for callId={} turnId={}",
                        units.size(), callId, currentTurnId);

                try {
                    for (int sIdx = 0; sIdx < units.size(); sIdx++) {
                        String textUnit = units.get(sIdx);
                        if (textUnit.isBlank()) continue;

                        if (turnContext.isCancelled()) {
                            log.info("⏹️ [WhatsAppVoiceBridge] Turn {} cancelled before synthesizing unit {}/{} for callId={}",
                                    currentTurnId, (sIdx + 1), units.size(), callId);
                            break;
                        }

                        final int unitNum = sIdx + 1;
                        final int totalUnits = units.size();

                        deepgramVoiceService.synthesizeSpeechStreaming(textUnit, ttsVoiceId, chunk -> {
                            if (turnContext.isCancelled()) {
                                log.debug("🔇 [WhatsAppVoiceBridge] Dropping TTS chunk because turn {} is cancelled", currentTurnId);
                                return;
                            }
                            // Only pass actual audio data during the turn; suppress intermediate EOFs so gateway never starves
                            if (chunk != null && chunk.length > 0) {
                                if (!firstChunkLogged.getAndSet(true)) {
                                    long ttsFirstChunkTime = System.currentTimeMillis();
                                    ttsFirstChunkMs.set(ttsFirstChunkTime);
                                    log.info("📈 [LATENCY-METRIC] Metric C: LLM response -> TTS first audio: {}ms (Unit {}/{})",
                                            (ttsFirstChunkTime - ttsStartMs), unitNum, totalUnits);
                                }
                                ttsChunkCallback.accept(chunk);
                            }
                        });
                    }
                } finally {
                    // Send the single definitive EOF signal to the WebRTC Gateway once ALL speech has been synthesized
                    ttsChunkCallback.accept(new byte[0]);
                }
            } else {
                // Fallback blocking if no callback
                byte[] synthesizedAudio = null;
                try {
                    synthesizedAudio = deepgramVoiceService.synthesizeSpeech(aiAnswer, ttsVoiceId);
                } catch (Exception ttsEx) {
                    log.error("❌ [WhatsAppVoiceBridge] Blocking TTS synthesis error: {}", ttsEx.getMessage());
                }
                ttsFirstChunkMs.set(System.currentTimeMillis());
                if (turnContext.isCancelled()) {
                     return new WhatsAppVoiceTurnResult(callId, "", userTranscript, false, new byte[0]);
                }
                persistVoiceTurns(callSession, tenantId, ttsVoiceId, userTranscript, isOverride, turnStartMs, sttEndMs,
                        aiAnswer, llmStartMs, llmEndMs, ttsStartMs, ttsFirstChunkMs.get(), turnContext.isCancelled());
                callContext.transitionTo(CallState.LISTENING);
                return new WhatsAppVoiceTurnResult(callId, aiAnswer, userTranscript, false, synthesizedAudio != null ? synthesizedAudio : new byte[0]);
            }
        } catch (Exception e) {
            log.error("❌ [WhatsAppVoiceBridge] TTS synthesis error: {}", e.getMessage());
        }

        // 5. Persist Voice Turns to Database for Call History & Real-Time Transcripts
        persistVoiceTurns(callSession, tenantId, ttsVoiceId, userTranscript, isOverride, turnStartMs, sttEndMs,
                aiAnswer, llmStartMs, llmEndMs, ttsStartMs, ttsFirstChunkMs.get(), turnContext.isCancelled());

        return new WhatsAppVoiceTurnResult(callId, aiAnswer, userTranscript, false, new byte[0]);
    }

    private void persistVoiceTurns(
            WhatsAppCallSession callSession,
            UUID tenantId,
            String ttsVoiceId,
            String userTranscript,
            boolean isOverride,
            long turnStartMs,
            long sttEndMs,
            String aiAnswer,
            long llmStartMs,
            long llmEndMs,
            long ttsStartMs,
            long firstTtsChunkTime,
            boolean wasInterrupted
    ) {
        if (voiceSessionRepository == null || voiceTurnRepository == null) {
            return;
        }
        try {
            VoiceSession vs = getOrCreateVoiceSession(callSession, tenantId, ttsVoiceId);
            int turnBase = (vs.getTotalTurns() != null ? vs.getTotalTurns() : 0);

            // 1. Persist caller utterance (if user spoke and not artificial greeting override)
            boolean isWelcomeGreeting = "Hello, I just connected".equalsIgnoreCase(userTranscript);
            if (userTranscript != null && !userTranscript.isBlank() && !isWelcomeGreeting) {
                turnBase++;
                VoiceTurn userTurn = new VoiceTurn();
                userTurn.setSession(vs);
                userTurn.setTurnNumber(turnBase);
                userTurn.setUserTranscript(userTranscript);
                userTurn.setSttLatencyMs((int) Math.max(0, sttEndMs - turnStartMs));
                userTurn.setDetectedLanguage("en");
                voiceTurnRepository.save(userTurn);
            }

            // 2. Persist AI assistant response
            if (aiAnswer != null && !aiAnswer.isBlank()) {
                turnBase++;
                VoiceTurn aiTurn = new VoiceTurn();
                aiTurn.setSession(vs);
                aiTurn.setTurnNumber(turnBase);
                aiTurn.setBotResponseText(aiAnswer);
                aiTurn.setLlmLatencyMs((int) Math.max(0, llmEndMs - llmStartMs));
                long firstChunk = firstTtsChunkTime > 0 ? firstTtsChunkTime : System.currentTimeMillis();
                aiTurn.setTtsLatencyMs((int) Math.max(0, firstChunk - ttsStartMs));
                aiTurn.setTtfaMs((int) Math.max(0, firstChunk - turnStartMs));
                aiTurn.setDetectedLanguage("en");
                aiTurn.setWasInterrupted(wasInterrupted);
                voiceTurnRepository.save(aiTurn);
            }

            vs.setTotalTurns(turnBase);
            vs.setActiveTurnNumber(turnBase);
            voiceSessionRepository.save(vs);
            log.debug("💾 [WhatsAppVoiceBridge] Persisted voice turns up to turnBase={} for callId={}", turnBase, callSession.getCallId());
        } catch (Exception e) {
            log.warn("⚠️ [WhatsAppVoiceBridge] Failed to persist call turns to DB for callId={}: {}", callSession.getCallId(), e.getMessage());
        }
    }

    private VoiceSession getOrCreateVoiceSession(WhatsAppCallSession callSession, UUID tenantId, String ttsVoiceId) {
        if (callSession.getAiSessionId() != null) {
            Optional<VoiceSession> opt = voiceSessionRepository.findById(callSession.getAiSessionId());
            if (opt.isPresent()) {
                return opt.get();
            }
        }

        VoiceSession vs = new VoiceSession();
        User businessUser = null;
        if (userRepository != null) {
            businessUser = userRepository.findFirstByTenantIdAndRole(tenantId, User.Role.ADMIN)
                    .or(() -> userRepository.findFirstByTenantIdAndRole(tenantId, User.Role.OWNER))
                    .or(() -> userRepository.findAllByTenant(callSession.getTenant()).stream().findFirst())
                    .or(() -> userRepository.findFirstUserIdByTenantId(tenantId).flatMap(userRepository::findById))
                    .orElse(null);
            if (businessUser == null) {
                businessUser = userRepository.findAll().stream().findFirst().orElse(null);
            }
        }

        vs.setBusiness(businessUser);
        vs.setTenant(callSession.getTenant());
        vs.setVisitorId(callSession.getFromWaId() != null ? callSession.getFromWaId() : "whatsapp_" + callSession.getCallId());
        vs.setStatus(VoiceSession.VoiceSessionStatus.ACTIVE);
        vs.setVoiceId(ttsVoiceId != null ? ttsVoiceId : "deepgram/aura");
        vs.setStartedAt(LocalDateTime.now());
        vs.setTotalTurns(0);
        vs = voiceSessionRepository.save(vs);

        callSession.setAiSessionId(vs.getId());
        callSessionRepository.save(callSession);
        return vs;
    }

    public record WhatsAppVoiceTurnResult(
        String callId,
        String aiResponseText,
        String userTranscript,
        boolean handoffTriggered,
        byte[] synthesizedAudio
    ) {}

    public void cleanupCallHistory(String callId) {
        callHistories.remove(callId);
    }
    
    public void terminateSession(String callId) {
        CallSessionContext callContext = activeCalls.remove(callId);
        if (callContext != null) {
            callContext.terminate();
        }
        cleanupCallHistory(callId);
        if (voiceSessionRepository != null && callSessionRepository != null) {
            try {
                WhatsAppCallSession cs = callSessionRepository.findByCallId(callId).orElse(null);
                if (cs != null && cs.getAiSessionId() != null) {
                    voiceSessionRepository.findById(cs.getAiSessionId()).ifPresent(vs -> {
                        vs.setStatus(VoiceSession.VoiceSessionStatus.COMPLETED);
                        vs.setEndedAt(LocalDateTime.now());
                        voiceSessionRepository.save(vs);
                    });
                }
            } catch (Exception e) {
                log.debug("Error updating voice session termination status for callId={}: {}", callId, e.getMessage());
            }
        }
    }
    
    public CallSessionContext getCallContext(String callId) {
        return activeCalls.get(callId);
    }
}

