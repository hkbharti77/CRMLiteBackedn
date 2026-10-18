package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceAssistantConfigRepository;
import com.chatcrmlite.backend.services.ai.DeepgramVoiceService;
import com.chatcrmlite.backend.services.ai.SarvamVoiceService;
import com.chatcrmlite.backend.services.voice.state.CallState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppVoiceCallBridgeTest {

    @Mock
    private VoiceSessionService voiceSessionService;

    @Mock
    private ConversationOrchestrator conversationOrchestrator;

    @Mock
    private VoiceHandoffService voiceHandoffService;

    @Mock
    private WhatsAppCallSessionRepository callSessionRepository;

    @Mock
    private DeepgramVoiceService deepgramVoiceService;

    @Mock
    private SarvamVoiceService sarvamVoiceService;

    @Mock
    private VoiceAssistantConfigRepository voiceConfigRepository;

    private WhatsAppVoiceCallBridgeService voiceBridge;

    private final UUID tenantId = UUID.randomUUID();
    private final String callId = "call_ai_test_01";
    private WhatsAppCallSession session;

    @BeforeEach
    void setUp() {
        voiceBridge = new WhatsAppVoiceCallBridgeService(
                voiceSessionService,
                conversationOrchestrator,
                voiceHandoffService,
                callSessionRepository,
                deepgramVoiceService,
                sarvamVoiceService,
                voiceConfigRepository
        );
        voiceBridge.setTtsProvider("deepgram");

        session = WhatsAppCallSession.builder()
                .callId(callId)
                .fromWaId("919876543210")
                .state("MEDIA_CONNECTED")
                .build();
        session.setTenantId(tenantId);

        lenient().when(callSessionRepository.findByTenantIdAndCallId(eq(tenantId), eq(callId)))
                .thenReturn(Optional.of(session));
        lenient().when(voiceConfigRepository.findByTenantId(tenantId))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Normal Turn: Audio -> STT -> LLM -> TTS callback delivers synthesized audio")
    void testNormalTurnPipeline() {
        byte[] audioBytes = new byte[]{1, 2, 3, 4};
        DeepgramVoiceService.DeepgramTranscriptionResult sttResult =
                DeepgramVoiceService.DeepgramTranscriptionResult.builder()
                        .transcript("Hello, how are you?")
                        .confidence(0.95)
                        .success(true)
                        .build();
        when(deepgramVoiceService.transcribeAudio(any(), any(), any())).thenReturn(sttResult);
        when(voiceHandoffService.evaluateHandoffRequired(anyString(), any(), anyInt())).thenReturn(false);
        when(conversationOrchestrator.executeTurn(anyString(), eq("Hello, how are you?"), anyList(), any()))
                .thenReturn("I am doing great, how can I help you?");
        when(deepgramVoiceService.synthesizeSpeech(anyString(), any())).thenReturn(new byte[]{5, 6, 7, 8});

        var result = voiceBridge.processCallTurn(tenantId, callId, audioBytes, "audio/wav", null, null);

        assertNotNull(result);
        assertEquals("I am doing great, how can I help you?", result.aiResponseText());
        assertEquals("Hello, how are you?", result.userTranscript());
        assertFalse(result.handoffTriggered());
        assertArrayEquals(new byte[]{5, 6, 7, 8}, result.synthesizedAudio());
    }

    @Test
    @DisplayName("STT Gate: Empty audio or low STT confidence (< 0.20) rejects turn without invoking LLM")
    void testSttGateLowConfidenceRejection() {
        byte[] audioBytes = new byte[]{1, 2, 3};
        DeepgramVoiceService.DeepgramTranscriptionResult lowConfidence =
                DeepgramVoiceService.DeepgramTranscriptionResult.builder()
                        .transcript("mumble noise")
                        .confidence(0.15) // < 0.20
                        .success(true)
                        .build();
        when(deepgramVoiceService.transcribeAudio(any(), any(), any())).thenReturn(lowConfidence);

        var result = voiceBridge.processCallTurn(tenantId, callId, audioBytes, "audio/wav", null, null);

        assertNotNull(result);
        assertEquals("", result.aiResponseText());
        verify(conversationOrchestrator, never()).executeTurn(any(), any(), any(), any());
        assertEquals(CallState.LISTENING, voiceBridge.getCallContext(callId).getState().get());
    }

    @Test
    @DisplayName("STT Provider Failure: Exception during transcribeAudio resets safely to LISTENING")
    void testSttProviderFailureSafeRecovery() {
        byte[] audioBytes = new byte[]{1, 2, 3};
        when(deepgramVoiceService.transcribeAudio(any(), any(), any())).thenThrow(new RuntimeException("Deepgram STT connection reset"));

        var result = voiceBridge.processCallTurn(tenantId, callId, audioBytes, "audio/wav", null, null);

        assertNotNull(result);
        assertEquals("", result.aiResponseText());
        verify(conversationOrchestrator, never()).executeTurn(any(), any(), any(), any());
        assertEquals(CallState.LISTENING, voiceBridge.getCallContext(callId).getState().get());
    }

    @Test
    @DisplayName("LLM Failure: Exception during LLM execution returns polite error and resets to LISTENING")
    void testLlmFailureGracefulFallback() {
        when(voiceHandoffService.evaluateHandoffRequired(anyString(), any(), anyInt())).thenReturn(false);
        when(conversationOrchestrator.executeTurn(anyString(), anyString(), anyList(), any()))
                .thenThrow(new RuntimeException("LLM Rate limit / Timeout"));

        var result = voiceBridge.processCallTurn(tenantId, callId, null, null, "Tell me about my order", null);

        assertNotNull(result);
        assertTrue(result.aiResponseText().contains("trouble processing"));
        assertEquals("Tell me about my order", result.userTranscript());
        assertEquals(CallState.LISTENING, voiceBridge.getCallContext(callId).getState().get());
    }

    @Test
    @DisplayName("TTS Failure: Synthesis exception completes safely without corrupting call session")
    void testTtsFailureDoesNotCorruptSession() {
        when(voiceHandoffService.evaluateHandoffRequired(anyString(), any(), anyInt())).thenReturn(false);
        when(conversationOrchestrator.executeTurn(anyString(), anyString(), anyList(), any()))
                .thenReturn("Here is your answer.");
        when(deepgramVoiceService.synthesizeSpeech(anyString(), any()))
                .thenThrow(new RuntimeException("Deepgram TTS network timeout"));

        var result = voiceBridge.processCallTurn(tenantId, callId, null, null, "Hello", null);

        assertNotNull(result);
        assertEquals("Here is your answer.", result.aiResponseText());
        assertNotNull(result.synthesizedAudio());
        assertEquals(0, result.synthesizedAudio().length);
    }

    @Test
    @DisplayName("Human Escalation: Explicit human request triggers handoff immediately")
    void testHumanHandoffTriggered() {
        when(voiceHandoffService.evaluateHandoffRequired(eq("I want to speak to a human agent"), any(), anyInt()))
                .thenReturn(true);

        var result = voiceBridge.processCallTurn(tenantId, callId, null, null, "I want to speak to a human agent", null);

        assertNotNull(result);
        assertTrue(result.handoffTriggered());
        assertTrue(result.aiResponseText().contains("human"));
        verify(voiceHandoffService, times(1)).executeHandoff(eq(tenantId), eq(callId), eq("USER_REQUESTED_HUMAN"));
        verify(conversationOrchestrator, never()).executeTurn(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Frustration Detection: Frustrated user text triggers escalation")
    void testFrustrationDetectionEscalation() {
        when(voiceHandoffService.evaluateHandoffRequired(contains("frustrated"), any(), anyInt()))
                .thenReturn(true);

        var result = voiceBridge.processCallTurn(tenantId, callId, null, null, "I am so angry and frustrated with this service", null);

        assertNotNull(result);
        assertTrue(result.handoffTriggered());
        verify(voiceHandoffService, times(1)).executeHandoff(eq(tenantId), eq(callId), eq("USER_REQUESTED_HUMAN"));
    }

    @Test
    @DisplayName("Multiple Turns: Preserves conversation history across turns")
    void testMultipleTurnsPreservesHistory() {
        when(voiceHandoffService.evaluateHandoffRequired(anyString(), any(), anyInt())).thenReturn(false);
        when(conversationOrchestrator.executeTurn(anyString(), eq("Turn 1"), anyList(), any()))
                .thenReturn("Answer 1");
        when(conversationOrchestrator.executeTurn(anyString(), eq("Turn 2"), anyList(), any()))
                .thenReturn("Answer 2");

        var turn1 = voiceBridge.processCallTurn(tenantId, callId, null, null, "Turn 1", null);
        assertEquals("Answer 1", turn1.aiResponseText());

        var turn2 = voiceBridge.processCallTurn(tenantId, callId, null, null, "Turn 2", null);
        assertEquals("Answer 2", turn2.aiResponseText());

        // Verify history had 2 messages (Turn 1 User + Turn 1 AI) on Turn 2 execution
        verify(conversationOrchestrator).executeTurn(anyString(), eq("Turn 2"), argThat(hist -> hist.size() == 2), any());
    }

    @Test
    @DisplayName("Context Isolation: Conversation memory for Call A does not leak into Call B")
    void testCallContextIsolationBetweenCalls() {
        String callB = "call_ai_test_02";
        WhatsAppCallSession sessionB = WhatsAppCallSession.builder()
                .callId(callB)
                .fromWaId("919999999999")
                .state("MEDIA_CONNECTED")
                .build();
        sessionB.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(eq(tenantId), eq(callB)))
                .thenReturn(Optional.of(sessionB));

        when(voiceHandoffService.evaluateHandoffRequired(anyString(), any(), anyInt())).thenReturn(false);
        when(conversationOrchestrator.executeTurn(anyString(), anyString(), anyList(), any()))
                .thenReturn("Answer");

        // Turn for Call A
        voiceBridge.processCallTurn(tenantId, callId, null, null, "Secret info for Call A", null);

        // Turn for Call B
        voiceBridge.processCallTurn(tenantId, callB, null, null, "Question for Call B", null);

        // Verify Call B history had 0 previous messages (completely isolated from Call A)
        verify(conversationOrchestrator).executeTurn(anyString(), eq("Question for Call B"), argThat(hist -> hist.isEmpty()), any());
    }

    @Test
    @DisplayName("Call Termination: Calling terminateSession cleans up state and cancels active turns")
    void testTerminateSessionCleansUpContext() {
        voiceBridge.processCallTurn(tenantId, callId, null, null, "Hello", null);
        assertNotNull(voiceBridge.getCallContext(callId));

        voiceBridge.terminateSession(callId);

        assertNull(voiceBridge.getCallContext(callId));
    }

    @Test
    @DisplayName("Sarvam TTS: Stream synthesized audio through callback when provider is sarvam")
    void testSarvamTtsStreamingPipeline() {
        voiceBridge.setTtsProvider("sarvam");
        voiceBridge.setSarvamSpeaker("simran");
        voiceBridge.setSarvamLanguage("hi-IN");

        when(voiceHandoffService.evaluateHandoffRequired(anyString(), any(), anyInt())).thenReturn(false);
        when(conversationOrchestrator.executeTurn(anyString(), eq("Namaste"), anyList(), any()))
                .thenReturn("Namaste! Mai aapki kya sahayata kar sakti hu?");

        // Mock Sarvam returning raw WAV audio (e.g. 44 bytes header + PCM)
        byte[] mockWav = new byte[100];
        mockWav[0] = 'R'; mockWav[1] = 'I'; mockWav[2] = 'F'; mockWav[3] = 'F';
        when(sarvamVoiceService.synthesizeSpeech(anyString(), eq("hi-IN"), eq("simran"))).thenReturn(mockWav);

        List<byte[]> receivedChunks = new ArrayList<>();
        var result = voiceBridge.processCallTurn(tenantId, callId, null, null, "Namaste", receivedChunks::add);

        assertNotNull(result);
        verify(sarvamVoiceService, atLeastOnce()).synthesizeSpeech(anyString(), eq("hi-IN"), eq("simran"));
        verify(deepgramVoiceService, never()).synthesizeSpeechStreaming(anyString(), any(), any());
        assertFalse(receivedChunks.isEmpty());
        // Last chunk is EOF (length 0)
        assertEquals(0, receivedChunks.get(receivedChunks.size() - 1).length);
    }

    @Test
    @DisplayName("Sarvam TTS Fallback: Falls back to Deepgram if Sarvam synthesis returns empty")
    void testSarvamTtsFallbackToDeepgram() {
        voiceBridge.setTtsProvider("sarvam");
        when(voiceHandoffService.evaluateHandoffRequired(anyString(), any(), anyInt())).thenReturn(false);
        when(conversationOrchestrator.executeTurn(anyString(), eq("Hello"), anyList(), any()))
                .thenReturn("Hello! How can I help you?");

        // Sarvam fails / returns empty
        when(sarvamVoiceService.synthesizeSpeech(anyString(), anyString(), anyString())).thenReturn(new byte[0]);

        List<byte[]> receivedChunks = new ArrayList<>();
        var result = voiceBridge.processCallTurn(tenantId, callId, null, null, "Hello", receivedChunks::add);

        assertNotNull(result);
        verify(sarvamVoiceService, atLeastOnce()).synthesizeSpeech(anyString(), anyString(), anyString());
        verify(deepgramVoiceService, atLeastOnce()).synthesizeSpeechStreaming(anyString(), any(), any());
    }
}
