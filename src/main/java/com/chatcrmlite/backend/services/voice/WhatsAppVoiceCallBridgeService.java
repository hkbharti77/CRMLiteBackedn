package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.services.ai.DeepgramVoiceService;
import com.chatcrmlite.backend.services.voice.tools.ToolExecutionContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import com.chatcrmlite.backend.services.voice.state.CallSessionContext;
import com.chatcrmlite.backend.services.voice.state.CallState;
import com.chatcrmlite.backend.services.voice.state.TurnContext;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppVoiceCallBridgeService {

    private final VoiceSessionService voiceSessionService;
    private final ConversationOrchestrator conversationOrchestrator;
    private final VoiceHandoffService voiceHandoffService;
    private final WhatsAppCallSessionRepository callSessionRepository;
    private final DeepgramVoiceService deepgramVoiceService;
    private final com.chatcrmlite.backend.services.ai.SarvamVoiceService sarvamVoiceService;
    private final com.chatcrmlite.backend.repositories.voice.VoiceAssistantConfigRepository voiceConfigRepository;

    // Call-scoped conversation memory
    private final ConcurrentHashMap<String, List<ChatMessage>> callHistories = new ConcurrentHashMap<>();
    
    // Call-scoped state machine
    private final ConcurrentHashMap<String, CallSessionContext> activeCalls = new ConcurrentHashMap<>();

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
        // 1. Speech-to-Text (STT) if audio bytes provided
        String userTranscript = clientTranscriptOverride;
        double sttConfidence = 0.0;
        if ((userTranscript == null || userTranscript.isBlank()) && pcmAudioBytes != null && pcmAudioBytes.length > 0) {
            try {
                DeepgramVoiceService.DeepgramTranscriptionResult sttResult =
                        deepgramVoiceService.transcribeAudio(pcmAudioBytes, mimeType != null ? mimeType : "audio/wav", "en");
                long sttEndMs = System.currentTimeMillis();
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
        
        log.info("▶️ [WhatsAppVoiceBridge] Starting Turn {} for callId={}. Transcript: '{}'", currentTurnId, callId, userTranscript);

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
        
        String aiAnswer = conversationOrchestrator.executeTurn(personaPrompt, userTranscript, new ArrayList<>(history.subList(0, history.size() - 1)), toolContext);
        long llmEndMs = System.currentTimeMillis();
        log.info("📈 [LATENCY-METRIC] Metric B: STT result -> LLM final response: {}ms", (llmEndMs - llmStartMs));
        
        // If empty response, do nothing
        if (aiAnswer == null || aiAnswer.trim().isEmpty()) {
             log.warn("⚠️ [WhatsAppVoiceBridge] LLM returned empty response for callId={} turnId={}", callId, currentTurnId);
             callContext.transitionTo(CallState.LISTENING);
             return new WhatsAppVoiceTurnResult(callId, "", userTranscript, false, new byte[0]);
        }

        // Ensure turn hasn't been cancelled before TTS
        try {
            turnContext.verifyActive();
        } catch (CancellationException e) {
            log.info("⏹️ [WhatsAppVoiceBridge] Turn {} cancelled before TTS for callId={}", currentTurnId, callId);
            return new WhatsAppVoiceTurnResult(callId, "", userTranscript, false, new byte[0]);
        }

        history.add(AiMessage.from(aiAnswer));

        log.info("🤖 [WhatsAppVoiceBridge] Tenant [{}] AI Answer for callId={} turnId={}: {}", tenantId, callId, currentTurnId, aiAnswer);

        // 4. Synthesize TTS Audio using Tenant's configured Voice
        callContext.transitionTo(CallState.SPEAKING);
        try {
            if (ttsChunkCallback != null) {
                // Execute streaming TTS
                final java.util.concurrent.atomic.AtomicBoolean firstChunkLogged = new java.util.concurrent.atomic.AtomicBoolean(false);
                long ttsStartMs = System.currentTimeMillis();
                deepgramVoiceService.synthesizeSpeechStreaming(aiAnswer, "aura-stella-en", chunk -> {
                    if (turnContext.isCancelled()) {
                        log.debug("🔇 [WhatsAppVoiceBridge] Dropping TTS chunk because turn {} is cancelled", currentTurnId);
                        return;
                    }
                    if (chunk != null && chunk.length > 0 && !firstChunkLogged.getAndSet(true)) {
                        long ttsFirstChunkMs = System.currentTimeMillis();
                        log.info("📈 [LATENCY-METRIC] Metric C: LLM response -> TTS first audio: {}ms", (ttsFirstChunkMs - ttsStartMs));
                    }
                    ttsChunkCallback.accept(chunk);
                });
            } else {
                // Fallback blocking if no callback
                byte[] synthesizedAudio = deepgramVoiceService.synthesizeSpeech(aiAnswer, "aura-stella-en");
                if (turnContext.isCancelled()) {
                     return new WhatsAppVoiceTurnResult(callId, "", userTranscript, false, new byte[0]);
                }
                return new WhatsAppVoiceTurnResult(callId, aiAnswer, userTranscript, false, synthesizedAudio);
            }
        } catch (Exception e) {
            log.error("❌ [WhatsAppVoiceBridge] TTS synthesis error: {}", e.getMessage());
        }
        
        // Once done speaking (or started streaming), return to listening (async streaming may still be ongoing, but logically we accept new input)
        // Wait, if streaming is ongoing, we are SPEAKING. The gateway handles barge-in by starting a new turn.
        // We shouldn't automatically transition to LISTENING here if async streaming is ongoing.

        return new WhatsAppVoiceTurnResult(callId, aiAnswer, userTranscript, false, new byte[0]);
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
    }
    
    public CallSessionContext getCallContext(String callId) {
        return activeCalls.get(callId);
    }
}

