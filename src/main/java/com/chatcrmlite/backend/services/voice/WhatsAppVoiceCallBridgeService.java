package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.services.ai.DeepgramVoiceService;
import com.chatcrmlite.backend.services.voice.tools.ToolExecutionContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

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

    /**
     * Processes a voice turn during a WhatsApp Call Session (Speech-to-Text -> LLM Tool Orchestration -> Text-to-Speech)
     */
    public WhatsAppVoiceTurnResult processCallTurn(UUID tenantId, String callId, byte[] pcmAudioBytes, String mimeType, String clientTranscriptOverride) {
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

        // 1. Speech-to-Text (STT) if audio bytes provided
        String userTranscript = clientTranscriptOverride;
        if ((userTranscript == null || userTranscript.isBlank()) && pcmAudioBytes != null && pcmAudioBytes.length > 0) {
            try {
                DeepgramVoiceService.DeepgramTranscriptionResult sttResult =
                        deepgramVoiceService.transcribeAudio(pcmAudioBytes, mimeType != null ? mimeType : "audio/wav", "en");
                if (sttResult != null && sttResult.getTranscript() != null) {
                    userTranscript = sttResult.getTranscript();
                }
            } catch (Exception e) {
                log.error("❌ [WhatsAppVoiceBridge] STT error for callId={}: {}", callId, e.getMessage());
            }
        }

        if (userTranscript == null || userTranscript.isBlank()) {
            userTranscript = "Hello";
        }

        // 2. Check Voice Safety & Human Handoff Guardrails
        boolean handoffNeeded = voiceHandoffService.evaluateHandoffRequired(userTranscript, null, 1);
        if (handoffNeeded) {
            voiceHandoffService.executeHandoff(tenantId, callId, "USER_REQUESTED_HUMAN");
            return new WhatsAppVoiceTurnResult(callId, "I am connecting you with a human customer support agent right now. Please stay on the line.", null, true, new byte[0]);
        }

        // 3. LLM Orchestration with Dynamic Tool Execution using Tenant's Persona
        ToolExecutionContext toolContext = new ToolExecutionContext(tenantId, null, callSession.getId(), null, callId, null, callSession.getFromWaId());
        String aiAnswer = conversationOrchestrator.executeTurn(personaPrompt, userTranscript, null, toolContext);

        log.info("🤖 [WhatsAppVoiceBridge] Tenant [{}] AI Answer for callId={}: {}", tenantId, callId, aiAnswer);

        // 4. Synthesize TTS Audio using Tenant's configured Voice
        byte[] synthesizedAudio = new byte[0];
        try {
            if (ttsVoiceId != null && (
                    ttsVoiceId.equalsIgnoreCase("simran") || 
                    ttsVoiceId.equalsIgnoreCase("priya") || 
                    ttsVoiceId.equalsIgnoreCase("neha") || 
                    ttsVoiceId.equalsIgnoreCase("rahul") || 
                    ttsVoiceId.equalsIgnoreCase("rohan") || 
                    ttsVoiceId.equalsIgnoreCase("amit") || 
                    ttsVoiceId.equalsIgnoreCase("aditya") || 
                    ttsVoiceId.equalsIgnoreCase("arvind") || 
                    ttsVoiceId.equalsIgnoreCase("ananya"))) {
                synthesizedAudio = sarvamVoiceService.synthesizeSpeech(aiAnswer, "hi-IN", ttsVoiceId.toLowerCase());
            }
            if (synthesizedAudio == null || synthesizedAudio.length == 0) {
                synthesizedAudio = deepgramVoiceService.synthesizeSpeech(aiAnswer, (ttsVoiceId != null && ttsVoiceId.startsWith("aura-")) ? ttsVoiceId : "aura-stella-en");
            }
        } catch (Exception e) {
            log.error("❌ [WhatsAppVoiceBridge] TTS synthesis error: {}", e.getMessage());
            synthesizedAudio = deepgramVoiceService.synthesizeSpeech(aiAnswer, "aura-stella-en");
        }

        return new WhatsAppVoiceTurnResult(callId, aiAnswer, userTranscript, false, synthesizedAudio);
    }

    public record WhatsAppVoiceTurnResult(
        String callId,
        String aiResponseText,
        String userTranscript,
        boolean handoffTriggered,
        byte[] synthesizedAudio
    ) {}
}

