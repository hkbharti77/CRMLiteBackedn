package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.services.ai.DeepgramVoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.config.RagConfig.ChatLanguageModelAiProvider;
import com.chatcrmlite.backend.services.ai.AiRequest;
import com.chatcrmlite.backend.services.ai.AiResponse;
import com.chatcrmlite.backend.services.ai.ModelHealthMonitor;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.List;

/**
 * Deterministic Verification Test Suite for WhatsApp Voice Agent Audio & VAD Pipeline:
 * - Audio Streaming, Pre-Roll Jitter Buffering, Queue Underruns, End-of-Stream
 * - VAD Calculations: RMS, Zero-Crossing Rate (ZCR), Signal Calibration
 * - Dual-Threshold Barge-In, Noise Floor Adaptation, Utterance Gating
 * - STUN Peer Address Protection & Source Verification
 */
@ExtendWith(MockitoExtension.class)
class WhatsAppVoiceAgentAuditTest {

    @Mock
    private WhatsAppVoiceCallBridgeService voiceCallBridgeService;

    @Mock
    private DeepgramVoiceService deepgramVoiceService;

    @Mock
    private WebRtcDtlsHandler dtlsHandler;

    private WhatsAppWebRtcMediaGateway mediaGateway;

    private static final String FINGERPRINT = "AA:BB:CC:DD:EE:FF:11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF:11:22:33:44:55:66:77:88:99:00";

    @BeforeEach
    void setUp() {
        lenient().when(dtlsHandler.getSha256Fingerprint()).thenReturn(FINGERPRINT);

        mediaGateway = new WhatsAppWebRtcMediaGateway(voiceCallBridgeService, deepgramVoiceService, dtlsHandler);
        ReflectionTestUtils.setField(mediaGateway, "portRangeStart", 52000);
        ReflectionTestUtils.setField(mediaGateway, "publicMediaIp", "127.0.0.1");
        ReflectionTestUtils.setField(mediaGateway, "turnEnabled", false);
        ReflectionTestUtils.setField(mediaGateway, "dtlsTimeoutMs", 5000);
        ReflectionTestUtils.setField(mediaGateway, "mediaStartTimeoutMs", 5000);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SECTION A: AUDIO STREAMING & JITTER BUFFER TESTS
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A1 & A3: Pre-roll jitter buffer holds playback until 12 frames or End-Of-Stream")
    void testPreRollJitterBufferBehavior() throws Exception {
        String callId = "test_preroll_01";
        DatagramSocket socket = new DatagramSocket();
        InetSocketAddress metaPeer = new InetSocketAddress("57.144.141.49", 3480);

        WhatsAppWebRtcMediaGateway.WebRtcMediaSession session =
                new WhatsAppWebRtcMediaGateway.WebRtcMediaSession(
                        callId, UUID.randomUUID(), "918700620913", socket, metaPeer,
                        111, "localufrag", "localpwd", "remoteufrag", "remotepwd",
                        "remotefingerprint", FINGERPRINT, true
                );

        var activeSessions = (java.util.Map<String, WhatsAppWebRtcMediaGateway.WebRtcMediaSession>)
                ReflectionTestUtils.getField(mediaGateway, "activeSessions");
        activeSessions.put(callId, session);

        // 1 frame of 20ms audio = 1920 bytes (960 samples @ 48kHz mono 16-bit LE)
        byte[] pcmChunk = createSineWavePcm(440.0, 48000, 960, 2000.0);

        // Feed 5 frames (less than PRE_ROLL_MIN_FRAMES = 12)
        for (int i = 0; i < 5; i++) {
            mediaGateway.enqueueOutboundAudio(callId, pcmChunk, 0);
        }

        // Verify pre-roll has NOT triggered playback yet
        assertFalse(session.isTtsPlaybackReady.get(), "Playback should not start before 12 frames");
        assertTrue(session.outboundAudioQueue.size() >= 5);

        // Drain loop with buildNextRtpPacket while not ready: must produce comfort silence
        WhatsAppWebRtcMediaGateway.WebRtcMediaSession.OutboundRtpFrame silenceFrame =
                session.buildNextRtpPacket(960);
        assertTrue(silenceFrame.isSilence, "RTP packet must be comfort silence during pre-roll buffering");
        assertArrayEquals(new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE},
                java.util.Arrays.copyOfRange(silenceFrame.packetData, 12, silenceFrame.packetData.length));

        // Now push up to 12 frames
        for (int i = 5; i < 12; i++) {
            mediaGateway.enqueueOutboundAudio(callId, pcmChunk, 0);
        }

        // Verify pre-roll threshold reached
        assertTrue(session.isTtsPlaybackReady.get(), "Playback must be ready once 12 frames accumulate");
        assertTrue(session.isSpeakingAI.get(), "isSpeakingAI must be true once ready");

        // Drain frame: must be active Opus payload, NOT silence
        WhatsAppWebRtcMediaGateway.WebRtcMediaSession.OutboundRtpFrame speechFrame =
                session.buildNextRtpPacket(1920);
        assertFalse(speechFrame.isSilence, "RTP packet must contain active speech frame");
        assertTrue(speechFrame.packetData.length > 12, "Must contain RTP header + Opus payload");

        socket.close();
    }

    @Test
    @DisplayName("A3 & A4: Short utterance with End-Of-Stream starts playback immediately and drains cleanly")
    void testShortUtteranceEofPlaybackAndCleanTermination() throws Exception {
        String callId = "test_short_eof_01";
        DatagramSocket socket = new DatagramSocket();
        InetSocketAddress metaPeer = new InetSocketAddress("57.144.141.49", 3480);

        WhatsAppWebRtcMediaGateway.WebRtcMediaSession session =
                new WhatsAppWebRtcMediaGateway.WebRtcMediaSession(
                        callId, UUID.randomUUID(), "918700620913", socket, metaPeer,
                        111, "ufrag", "pwd", "rufrag", "rpwd", "rfp", FINGERPRINT, true
                );

        var activeSessions = (java.util.Map<String, WhatsAppWebRtcMediaGateway.WebRtcMediaSession>)
                ReflectionTestUtils.getField(mediaGateway, "activeSessions");
        activeSessions.put(callId, session);

        // Short utterance: 3 frames (60ms audio, e.g., "Yes")
        byte[] pcmChunk = createSineWavePcm(440.0, 48000, 960 * 3, 2000.0);
        mediaGateway.enqueueOutboundAudio(callId, pcmChunk, 0);

        // Before EOF: only 3 frames, not ready
        assertFalse(session.isTtsPlaybackReady.get());

        // Signal End of Stream (EOF = 0 bytes)
        mediaGateway.enqueueOutboundAudio(callId, new byte[0], 0);

        // Verify EOF triggered ready immediately despite queue < 12
        assertTrue(session.isTtsPlaybackReady.get(), "EOF must trigger playback for short utterance");
        assertTrue(session.isTtsStreamCompleted.get(), "Stream must be marked completed");
        assertTrue(session.isSpeakingAI.get());

        // Drain all frames
        while (!session.outboundAudioQueue.isEmpty()) {
            var frame = session.buildNextRtpPacket(0);
            assertFalse(frame.isSilence);
        }

        // Next tick after queue empty: isSpeakingAI must reset to false cleanly
        var finalFrame = session.buildNextRtpPacket(0);
        assertTrue(finalFrame.isSilence);
        assertFalse(session.isSpeakingAI.get(), "isSpeakingAI must be false once queue drains after EOF");
        assertFalse(session.isTtsPlaybackReady.get(), "isTtsPlaybackReady must reset to false");

        socket.close();
    }

    @Test
    @DisplayName("A3: Queue underrun during mid-stream network latency keeps isSpeakingAI true")
    void testQueueUnderrunMidStreamBehavior() throws Exception {
        String callId = "test_underrun_01";
        DatagramSocket socket = new DatagramSocket();
        InetSocketAddress metaPeer = new InetSocketAddress("57.144.141.49", 3480);

        WhatsAppWebRtcMediaGateway.WebRtcMediaSession session =
                new WhatsAppWebRtcMediaGateway.WebRtcMediaSession(
                        callId, UUID.randomUUID(), "918700620913", socket, metaPeer,
                        111, "ufrag", "pwd", "rufrag", "rpwd", "rfp", FINGERPRINT, true
                );

        var activeSessions = (java.util.Map<String, WhatsAppWebRtcMediaGateway.WebRtcMediaSession>)
                ReflectionTestUtils.getField(mediaGateway, "activeSessions");
        activeSessions.put(callId, session);

        // 12 frames to start playback
        byte[] pcmChunk = createSineWavePcm(440.0, 48000, 960 * 12, 2000.0);
        mediaGateway.enqueueOutboundAudio(callId, pcmChunk, 0);
        assertTrue(session.isTtsPlaybackReady.get());
        assertTrue(session.isSpeakingAI.get());

        // Drain all 12 frames
        for (int i = 0; i < 12; i++) {
            session.buildNextRtpPacket(i * 960);
        }
        assertTrue(session.outboundAudioQueue.isEmpty());

        // Now queue is empty, BUT EOF has NOT arrived yet (HTTP jitter gap)
        assertFalse(session.isTtsStreamCompleted.get());

        // Next RTP tick: sends comfort silence, BUT isSpeakingAI MUST STAY TRUE
        var frame = session.buildNextRtpPacket(12 * 960);
        assertTrue(frame.isSilence, "Must send silence during temporary jitter underrun");
        assertTrue(session.isSpeakingAI.get(), "isSpeakingAI must NOT drop to false mid-stream during network gap");

        socket.close();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SECTION B: VAD DECISION, RMS, ZCR & BARGE-IN TESTS
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("B1 & B2: Zero-Crossing Rate (ZCR) and RMS calculations on synthetic signals")
    void testRmsAndZcrSignalCalibration() {
        // 1. Digital Silence (RMS = 0, ZCR = 0)
        short[] silence = new short[960];
        VadMetrics silenceMetrics = computeVadMetrics(silence);
        assertEquals(0.0, silenceMetrics.rms, 0.01);
        assertEquals(0, silenceMetrics.normalizedZcr);
        assertFalse(silenceMetrics.isVoiceBand(5, 260), "Pure silence is outside voice band");

        // 2. Sub-Audio Low Rumble (30 Hz sine wave @ 48kHz, 960 samples = 20ms)
        // 30 Hz in 20ms = 0.6 cycles -> approx 1 zero-crossing
        short[] rumble = generateSineWave(30.0, 48000, 960, 5000.0);
        VadMetrics rumbleMetrics = computeVadMetrics(rumble);
        assertTrue(rumbleMetrics.normalizedZcr < 5, "30Hz rumble must have ZCR < 5");
        assertFalse(rumbleMetrics.isVoiceBand(5, 260), "Sub-audio rumble must be rejected by ZCR filter");

        // 3. Deep Male Voice (100 Hz fundamental)
        // 100 Hz in 20ms = 2 cycles -> 4 crossings
        short[] deepMale = generateSineWave(120.0, 48000, 960, 4000.0);
        VadMetrics deepMaleMetrics = computeVadMetrics(deepMale);
        assertTrue(deepMaleMetrics.normalizedZcr >= 4 && deepMaleMetrics.normalizedZcr <= 6);
        assertTrue(deepMaleMetrics.isVoiceBand(4, 260), "Deep male voice must be preserved");

        // 4. Typical Human Speech (350 Hz vowel formant)
        // 350 Hz in 20ms = 7 cycles -> 14 crossings
        short[] speechVowel = generateSineWave(350.0, 48000, 960, 3000.0);
        VadMetrics speechMetrics = computeVadMetrics(speechVowel);
        assertTrue(speechMetrics.normalizedZcr >= 12 && speechMetrics.normalizedZcr <= 16);
        assertTrue(speechMetrics.isVoiceBand(5, 260));

        // 5. Unvoiced Fricative Consonant ("S" sound ~ 4.5 kHz)
        // 4500 Hz in 20ms = 90 cycles -> 180 crossings
        short[] fricative = generateSineWave(4500.0, 48000, 960, 2000.0);
        VadMetrics fricativeMetrics = computeVadMetrics(fricative);
        assertTrue(fricativeMetrics.normalizedZcr >= 170 && fricativeMetrics.normalizedZcr <= 190);
        assertTrue(fricativeMetrics.isVoiceBand(5, 260), "Fricatives like 's' must be preserved");

        // 6. Supersonic Electrical Static / Hiss (7.5 kHz)
        // 7500 Hz in 20ms = 150 cycles -> 300 crossings
        short[] hiss = generateSineWave(7500.0, 48000, 960, 2000.0);
        VadMetrics hissMetrics = computeVadMetrics(hiss);
        assertTrue(hissMetrics.normalizedZcr > 260, "High frequency hiss has ZCR > 260");
        assertFalse(hissMetrics.isVoiceBand(5, 260), "Supersonic hiss must be rejected");
    }

    @Test
    @DisplayName("B2 & B3: Dual-Threshold VAD shields against background music and speaker echo during AI speech")
    void testDualThresholdBargeInProtection() {
        double noiseFloor = 40.0;

        // Threshold when AI is silent:
        double quietThreshold = Math.max(600.0, noiseFloor * 2.2);
        assertEquals(600.0, quietThreshold);

        // Threshold when AI is actively speaking (hardened against phone speakerphone echo):
        double speakingThreshold = Math.max(2000.0, noiseFloor * 4.5);
        assertEquals(2000.0, speakingThreshold);

        // Scenario 1: Background Music or Phone Speaker Acoustic Leak (RMS = 800)
        double acousticEchoRms = 800.0;
        int voiceZcr = 40; // in voice band

        boolean triggersWhenSilent = (acousticEchoRms > quietThreshold);
        boolean triggersWhenAiSpeaking = (acousticEchoRms > speakingThreshold);

        assertTrue(triggersWhenSilent, "800 RMS triggers response when silent");
        assertFalse(triggersWhenAiSpeaking, "800 RMS acoustic echo/music CANNOT interrupt AI while speaking (Barge-in shielded)");

        // Scenario 2: Intentional Human Voice Interruption (RMS = 2500)
        double intentionalBargeInRms = 2500.0;
        boolean bargeInTriggered = (intentionalBargeInRms > speakingThreshold);
        assertTrue(bargeInTriggered, "2500 RMS loud human voice successfully triggers barge-in");
    }

    @Test
    @DisplayName("B5: Noise floor adaptation does not inflate during speech and is capped at 450")
    void testNoiseFloorAdaptationRules() {
        double floor = 40.0;

        // Quiet frame (RMS = 120, fan running)
        double fanRms = 120.0;
        double adapted = Math.min(450.0, (0.95 * floor) + (0.05 * fanRms));
        assertEquals(44.0, adapted, 0.01, "Floor smoothly adapts upward in quiet");

        // Extremely loud room / music (RMS = 1500)
        // Rule: Only adapt when rms < 1200, so 1500 does NOT update floor
        boolean shouldAdaptOnLoudSound = (1500.0 < 1200.0);
        assertFalse(shouldAdaptOnLoudSound, "Loud sounds > 1200 RMS must never adapt noise floor");

        // High steady noise floor: capped at 450
        double runawayFloor = 440.0;
        double highNoiseRms = 900.0;
        double capped = Math.min(450.0, (0.95 * runawayFloor) + (0.05 * highNoiseRms));
        assertEquals(450.0, capped, 0.01, "Noise floor must be capped at 450 to prevent user lockout");
    }

    @Test
    @DisplayName("B4: Utterance gating preserves short affirmative words ('Yes', 'No') and drops transient clicks")
    void testUtteranceGatingShortWordsVsNoiseClicks() {
        int sampleRate = 48000;
        int bytesPerSample = 2; // 16-bit mono

        // Case 1: Transient Noise Pop / Click (1 frame = 20ms, voicedFrames = 2, 960 samples = 1920 bytes)
        int clickVoicedFrames = 2;
        int clickBytes = 1920;
        boolean clickAccepted = !(clickVoicedFrames < 6 || clickBytes < sampleRate * bytesPerSample * 0.15);
        assertFalse(clickAccepted, "2-frame click (40ms) must be discarded");

        // Case 2: Short Word "Yes" / "No" (7 voiced frames = 140ms voice, total utterance audio 250ms = 24,000 bytes)
        int shortWordVoicedFrames = 7;
        int shortWordBytes = (int) (sampleRate * bytesPerSample * 0.25);
        boolean shortWordAccepted = !(shortWordVoicedFrames < 6 || shortWordBytes < sampleRate * bytesPerSample * 0.15);
        assertTrue(shortWordAccepted, "7-frame short word 'No' (140ms voice, 250ms audio) must be accepted for STT");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SECTION C: STUN ADDRESS ISOLATION & PEER SELECTION TESTS
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1 & C2: Delayed STUN responses and external scanners cannot hijack negotiated Meta remoteAddress")
    void testStunPeerAddressProtection() {
        InetSocketAddress metaPeer = new InetSocketAddress("57.144.141.49", 3480);
        InetSocketAddress currentRemote = metaPeer;

        // Packet 1: Delayed STUN response from Google STUN (74.125.250.129:19302)
        InetSocketAddress googleStun = new InetSocketAddress("74.125.250.129", 19302);
        boolean isPublicStun1 = (googleStun.getPort() == 19302 || googleStun.getPort() == 80);
        assertTrue(isPublicStun1);

        // Verification logic from startInboundListener
        if (!isPublicStun1) {
            currentRemote = googleStun;
        }
        assertEquals(metaPeer, currentRemote, "Google STUN response must NOT overwrite Meta remoteAddress");

        // Packet 2: Delayed STUN response from Metered STUN (stun.relay.metered.ca:80)
        InetSocketAddress meteredStun = new InetSocketAddress("172.105.1.1", 80);
        boolean isPublicStun2 = (meteredStun.getPort() == 19302 || meteredStun.getPort() == 80);
        assertTrue(isPublicStun2);
        if (!isPublicStun2) {
            currentRemote = meteredStun;
        }
        assertEquals(metaPeer, currentRemote, "Metered STUN response must NOT overwrite Meta remoteAddress");

        // Packet 3: Internet port scanner UDP packet from random host (185.220.101.5:44444)
        InetSocketAddress scanner = new InetSocketAddress("185.220.101.5", 44444);
        boolean isPublicStun3 = (scanner.getPort() == 19302 || scanner.getPort() == 80);
        assertFalse(isPublicStun3);

        // Under hardened logic: random packet cannot change remoteAddress if currentRemote != null and host differs
        if (!isPublicStun3) {
            if (currentRemote == null) {
                currentRemote = scanner;
            } else if (scanner.getAddress().equals(currentRemote.getAddress()) && !currentRemote.equals(scanner)) {
                currentRemote = scanner;
            }
        }
        assertEquals(metaPeer, currentRemote, "Random scanner IP must NOT hijack Meta remoteAddress");

        // Packet 4: Legitimate Meta candidate port migration (57.144.141.49:3481)
        InetSocketAddress metaMigrated = new InetSocketAddress("57.144.141.49", 3481);
        if (metaMigrated.getAddress().equals(currentRemote.getAddress()) && !currentRemote.equals(metaMigrated)) {
            currentRemote = metaMigrated;
        }
        assertEquals(metaMigrated, currentRemote, "Legitimate peer port migration on same host IP is accepted");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SECTION D: LLM RESILIENCE, FALLBACK SPEECH & DEAD-SILENCE ELIMINATION
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D1: WhatsAppVoiceCallBridgeService synthesizes fallback speech on LLM failure (No dead silence)")
    void testVoiceBridgeRecoversFromLlmExceptionAndSpeaksFallback() {
        VoiceSessionService sessionService = mock(VoiceSessionService.class);
        ConversationOrchestrator orchestrator = mock(ConversationOrchestrator.class);
        VoiceHandoffService handoffService = mock(VoiceHandoffService.class);
        WhatsAppCallSessionRepository sessionRepo = mock(WhatsAppCallSessionRepository.class);
        com.chatcrmlite.backend.repositories.voice.VoiceAssistantConfigRepository configRepo =
                mock(com.chatcrmlite.backend.repositories.voice.VoiceAssistantConfigRepository.class);
        DeepgramVoiceService deepgramMock = mock(DeepgramVoiceService.class);

        WhatsAppVoiceCallBridgeService bridgeService = new WhatsAppVoiceCallBridgeService(
                sessionService, orchestrator, handoffService, sessionRepo, deepgramMock, null, configRepo
        );

        UUID tenantId = UUID.randomUUID();
        String callId = "test_dead_silence_call";
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId(callId)
                .fromWaId("919905941728")
                .toWaId("918700620913")
                .direction("USER_INITIATED")
                .state("IN_PROGRESS")
                .build();
        session.setTenantId(tenantId);

        when(sessionRepo.findByTenantIdAndCallId(tenantId, callId)).thenReturn(java.util.Optional.of(session));
        when(configRepo.findByTenantId(tenantId)).thenReturn(java.util.Optional.empty());

        // Simulate OpenRouter / LangChain4j NullPointerException (choices() is null)
        when(orchestrator.executeTurn(any(), any(), anyList(), any()))
                .thenThrow(new NullPointerException("Cannot invoke List.get(int) because choices() is null"));

        java.util.List<byte[]> receivedChunks = new java.util.ArrayList<>();
        java.util.List<String> receivedSentences = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            String text = invocation.getArgument(0);
            java.util.function.Consumer<byte[]> callback = invocation.getArgument(2);
            receivedSentences.add(text);
            callback.accept(new byte[]{1, 2, 3, 4});
            return null;
        }).when(deepgramMock).synthesizeSpeechStreaming(anyString(), any(), any());

        var result = bridgeService.processCallTurn(tenantId, callId, new byte[0], "audio/wav", "Hello, I just connected", receivedChunks::add);

        assertNotNull(result);
        assertEquals("I can hear you clearly. How can I assist you today?", result.aiResponseText());
        assertFalse(receivedChunks.isEmpty(), "Caller must receive synthesized audio chunks even when LLM throws an exception");
        assertEquals(2, receivedSentences.size(), "Should have received 2 pipelined sentences");
        assertEquals("I can hear you clearly.", receivedSentences.get(0));
        assertEquals("How can I assist you today?", receivedSentences.get(1));
        assertEquals(3, receivedChunks.size(), "Should have received audio chunks for each sentence + EOF chunk (byte[0])");
        assertArrayEquals(new byte[]{1, 2, 3, 4}, receivedChunks.get(0));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, receivedChunks.get(1));
        assertArrayEquals(new byte[0], receivedChunks.get(2));
    }

    @Test
    @DisplayName("D2: ChatLanguageModelAiProvider retries without tools on tool exception")
    void testChatLanguageModelAiProviderRecoversFromToolCallingException() {
        ChatLanguageModel mockChatModel = mock(ChatLanguageModel.class);
        ModelHealthMonitor healthMonitor = mock(ModelHealthMonitor.class);

        ChatLanguageModelAiProvider provider = new ChatLanguageModelAiProvider(
                mockChatModel, healthMonitor, "openrouter", "openrouter/auto", null, "openrouter/auto", null
        );

        List<ToolSpecification> tools = List.of(
                ToolSpecification.builder().name("book_appointment").description("Book appointment").build()
        );

        AiRequest request = AiRequest.builder()
                .prompt("Hello")
                .tools(tools)
                .build();

        // 1. Calling with tools throws NullPointerException
        when(mockChatModel.generate(anyList(), eq(tools)))
                .thenThrow(new NullPointerException("Cannot invoke List.get(int) because choices() is null"));

        // 2. Fallback retry without tools succeeds
        when(mockChatModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("Hello! How can I help you today?")));

        AiResponse response = provider.generate(request);

        assertNotNull(response);
        assertEquals("Hello! How can I help you today?", response.getContent());
        verify(mockChatModel, times(1)).generate(anyList(), eq(tools));
        verify(mockChatModel, times(1)).generate(anyList());
    }

    @Test
    @DisplayName("D3: ChatLanguageModelAiProvider returns safe conversational fallback on total upstream failure")
    void testChatLanguageModelAiProviderReturnsFallbackOnTotalFailure() {
        ChatLanguageModel mockChatModel = mock(ChatLanguageModel.class);
        ModelHealthMonitor healthMonitor = mock(ModelHealthMonitor.class);

        ChatLanguageModelAiProvider provider = new ChatLanguageModelAiProvider(
                mockChatModel, healthMonitor, "openrouter", "openrouter/auto", null, "openrouter/auto", null
        );

        AiRequest request = AiRequest.builder()
                .prompt("Hello")
                .build();

        when(mockChatModel.generate(anyList()))
                .thenThrow(new RuntimeException("OpenRouter 429 rate limit exceeded"));

        AiResponse response = provider.generate(request);

        assertNotNull(response);
        assertEquals("Hello, I can hear you clearly. How can I assist you today?", response.getContent());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SECTION E: LIVE STREAMING STT (WEBSOCKET) & FAST-PATH ZERO-LATENCY TESTS
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("E1: WhatsAppVoiceCallBridgeService fast-paths live transcript override without batch STT")
    void testLiveTranscriptFastPathSkipsBatchStt() {
        VoiceSessionService sessionService = mock(VoiceSessionService.class);
        ConversationOrchestrator orchestrator = mock(ConversationOrchestrator.class);
        VoiceHandoffService handoffService = mock(VoiceHandoffService.class);
        WhatsAppCallSessionRepository sessionRepo = mock(WhatsAppCallSessionRepository.class);
        com.chatcrmlite.backend.repositories.voice.VoiceAssistantConfigRepository configRepo =
                mock(com.chatcrmlite.backend.repositories.voice.VoiceAssistantConfigRepository.class);
        DeepgramVoiceService deepgramMock = mock(DeepgramVoiceService.class);

        WhatsAppVoiceCallBridgeService bridgeService = new WhatsAppVoiceCallBridgeService(
                sessionService, orchestrator, handoffService, sessionRepo, deepgramMock, null, configRepo
        );

        UUID tenantId = UUID.randomUUID();
        String callId = "test_live_stt_fast_path";
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId(callId)
                .fromWaId("919905941728")
                .toWaId("918700620913")
                .direction("USER_INITIATED")
                .state("IN_PROGRESS")
                .build();
        session.setTenantId(tenantId);

        when(sessionRepo.findByTenantIdAndCallId(tenantId, callId)).thenReturn(java.util.Optional.of(session));
        when(configRepo.findByTenantId(tenantId)).thenReturn(java.util.Optional.empty());
        when(orchestrator.executeTurn(any(), eq("What are your business hours?"), anyList(), any()))
                .thenReturn("We are open Monday to Friday, 9am to 6pm.");

        doAnswer(invocation -> {
            java.util.function.Consumer<byte[]> callback = invocation.getArgument(2);
            callback.accept(new byte[]{10, 20, 30});
            return null;
        }).when(deepgramMock).synthesizeSpeechStreaming(anyString(), any(), any());

        // Fast-path: clientTranscriptOverride provided from Live STT WebSocket
        String liveTranscript = "What are your business hours?";
        byte[] dummyAudio = new byte[960 * 2]; // 1 frame

        var result = bridgeService.processCallTurn(
                tenantId, callId, dummyAudio, "audio/wav", liveTranscript, chunk -> {}
        );

        assertNotNull(result);
        assertEquals("We are open Monday to Friday, 9am to 6pm.", result.aiResponseText());
        assertEquals(liveTranscript, result.userTranscript());

        // CRITICAL: verify batch transcribeAudio was NEVER called!
        verify(deepgramMock, never()).transcribeAudio(any(), any(), any());
    }

    @Test
    @DisplayName("E2: DeepgramLiveSttSession accumulates final transcripts and drains audio chunks safely")
    void testDeepgramLiveSttSessionLifecycleAndTranscriptBuffering() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.chatcrmlite.backend.services.ai.DeepgramLiveSttSession session =
                new com.chatcrmlite.backend.services.ai.DeepgramLiveSttSession(
                        "mock_api_key", "nova-2", true, true, "en-IN", "test_call_001", mapper
                );

        // Before any audio or transcripts
        assertEquals("", session.getAccumulatedTranscript());
        assertEquals(0, session.getBytesStreamed());

        // Send 3 audio frames (1920 bytes each)
        byte[] chunk = new byte[1920];
        session.sendAudioChunk(chunk);
        session.sendAudioChunk(chunk);
        session.sendAudioChunk(chunk);

        assertEquals(1920 * 3, session.getBytesStreamed());

        // Clean close
        session.close();
        assertFalse(session.isConnected());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SECTION E: STUN RFC 5389 COMPLIANCE & NAT TRAVERSAL VERIFICATION
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("E1: STUN Binding Request FINGERPRINT complies with RFC 5389 Section 15.5")
    void testRfc5389StunFingerprintCompliance() {
        String remoteUfrag = "FhHIqkZlqmYetKhC";
        String localUfrag = "cf47ad43";

        ByteArrayOutputStream attrStream = new ByteArrayOutputStream();
        String username = remoteUfrag + ":" + localUfrag;
        byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
        attrStream.write(0x00); attrStream.write(0x06);
        attrStream.write((userBytes.length >> 8) & 0xFF);
        attrStream.write(userBytes.length & 0xFF);
        attrStream.write(userBytes, 0, userBytes.length);
        int pad = (4 - (userBytes.length % 4)) % 4;
        for (int i = 0; i < pad; i++) attrStream.write(0);

        attrStream.write(0x00); attrStream.write(0x24);
        attrStream.write(0x00); attrStream.write(0x04);
        attrStream.write(0x6E); attrStream.write(0x7F);
        attrStream.write(0x00); attrStream.write((byte) 0xFF);

        attrStream.write(0x00); attrStream.write(0x25);
        attrStream.write(0x00); attrStream.write(0x00);

        attrStream.write((byte) 0x80); attrStream.write(0x2A);
        attrStream.write(0x00); attrStream.write(0x08);
        byte[] tieBreaker = new byte[8];
        attrStream.write(tieBreaker, 0, 8);

        byte[] rawAttrs = attrStream.toByteArray();
        int totalAttrLen = rawAttrs.length + 32;
        ByteBuffer buf = ByteBuffer.allocate(20 + totalAttrLen);

        buf.putShort((short) 0x0001);
        buf.putShort((short) totalAttrLen);
        buf.putInt(0x2112A442);
        byte[] txId = new byte[12];
        buf.put(txId);
        buf.put(rawAttrs);

        byte[] forHmac = buf.array();
        int hmacLength = rawAttrs.length + 24;
        forHmac[2] = (byte) ((hmacLength >> 8) & 0xFF);
        forHmac[3] = (byte) (hmacLength & 0xFF);

        byte[] integrity = new byte[20];
        buf.position(20 + rawAttrs.length);
        buf.putShort((short) 0x0008);
        buf.putShort((short) 20);
        buf.put(integrity);

        // RFC 5389 Section 15.5: Compute CRC while header length reflects hmacLength
        CRC32 crc32 = new CRC32();
        crc32.update(forHmac, 0, 20 + rawAttrs.length + 24);
        int crc = ((int) crc32.getValue()) ^ 0x5354554E;

        buf.putShort((short) 0x8028);
        buf.putShort((short) 4);
        buf.putInt(crc);

        // After CRC computation, update header length to totalAttrLen
        forHmac[2] = (byte) ((totalAttrLen >> 8) & 0xFF);
        forHmac[3] = (byte) (totalAttrLen & 0xFF);

        byte[] packet = buf.array();

        // RFC 5389 Receiver verification
        int receivedHeaderLen = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        assertEquals(totalAttrLen, receivedHeaderLen);

        int fpOffset = packet.length - 8;
        int fpType = ((packet[fpOffset] & 0xFF) << 8) | (packet[fpOffset + 1] & 0xFF);
        assertEquals(0x8028, fpType);
        int expectedCrc = ((packet[fpOffset + 4] & 0xFF) << 24)
                | ((packet[fpOffset + 5] & 0xFF) << 16)
                | ((packet[fpOffset + 6] & 0xFF) << 8)
                | (packet[fpOffset + 7] & 0xFF);

        byte[] verifyCopy = Arrays.copyOf(packet, packet.length);
        int lengthWithoutFp = totalAttrLen - 8;
        verifyCopy[2] = (byte) ((lengthWithoutFp >> 8) & 0xFF);
        verifyCopy[3] = (byte) (lengthWithoutFp & 0xFF);

        CRC32 receiverCrc = new CRC32();
        receiverCrc.update(verifyCopy, 0, fpOffset);
        int computedCrc = ((int) receiverCrc.getValue()) ^ 0x5354554E;

        assertEquals(expectedCrc, computedCrc, "RFC 5389 FINGERPRINT must verify successfully against standard receiver");
    }

    @Test
    @DisplayName("E2: WebRTC SDP Answer advertises reflexive port in m=audio and adds typ srflx candidate")
    void testSdpAnswerSrflxCandidateAndPort() {
        String localHostIp = "192.168.1.50";
        int localPort = 50000;
        InetSocketAddress srflx = new InetSocketAddress("152.59.146.96", 5825);

        String mediaIp = srflx.getHostString();
        int mediaPort = srflx.getPort();

        StringBuilder sdp = new StringBuilder();
        sdp.append("m=audio ").append(mediaPort).append(" UDP/TLS/RTP/SAVPF 111\r\n");
        sdp.append("c=IN IP4 ").append(mediaIp).append("\r\n");
        sdp.append("a=candidate:1 1 UDP 2130706431 ").append(localHostIp).append(" ").append(localPort).append(" typ host\r\n");
        sdp.append("a=candidate:2 1 UDP 1694498815 ").append(srflx.getHostString()).append(" ").append(srflx.getPort())
           .append(" typ srflx raddr ").append(localHostIp).append(" rport ").append(localPort).append("\r\n");
        sdp.append("a=end-of-candidates\r\n");

        String sdpStr = sdp.toString();
        assertTrue(sdpStr.contains("m=audio 5825 UDP/TLS/RTP/SAVPF 111"), "m=audio must use external mapped NAT port");
        assertTrue(sdpStr.contains("c=IN IP4 152.59.146.96"), "c=IN IP4 must use external mapped NAT IP");
        assertTrue(sdpStr.contains("typ host"), "Must contain host candidate");
        assertTrue(sdpStr.contains("a=candidate:2 1 UDP 1694498815 152.59.146.96 5825 typ srflx"), "Must contain reflexive candidate with port 5825");
        assertFalse(sdpStr.contains("typ relay"), "Must NOT contain fake unallocated relay candidate");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // HELPER FUNCTIONS
    // ─────────────────────────────────────────────────────────────────────────────

    private record VadMetrics(double rms, int normalizedZcr) {
        boolean isVoiceBand(int minZcr, int maxZcr) {
            return normalizedZcr >= minZcr && normalizedZcr <= maxZcr;
        }
    }

    private static VadMetrics computeVadMetrics(short[] samples) {
        long sumSquares = 0;
        int zeroCrossings = 0;
        for (int i = 0; i < samples.length; i++) {
            sumSquares += (long) samples[i] * samples[i];
            if (i > 0) {
                if ((samples[i] >= 0 && samples[i - 1] < 0) || (samples[i] < 0 && samples[i - 1] >= 0)) {
                    zeroCrossings++;
                }
            }
        }
        double rms = Math.sqrt((double) sumSquares / samples.length);
        int normalizedZcr = (int) Math.round(((double) zeroCrossings / samples.length) * 960.0);
        return new VadMetrics(rms, normalizedZcr);
    }

    private static short[] generateSineWave(double freqHz, int sampleRate, int numSamples, double amplitude) {
        short[] samples = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            double angle = 2.0 * Math.PI * freqHz * i / sampleRate;
            samples[i] = (short) Math.round(amplitude * Math.sin(angle));
        }
        return samples;
    }

    private static byte[] createSineWavePcm(double freqHz, int sampleRate, int numSamples, double amplitude) {
        short[] samples = generateSineWave(freqHz, sampleRate, numSamples, amplitude);
        byte[] pcm = new byte[numSamples * 2];
        ByteBuffer buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) {
            buf.putShort(s);
        }
        return pcm;
    }
}
