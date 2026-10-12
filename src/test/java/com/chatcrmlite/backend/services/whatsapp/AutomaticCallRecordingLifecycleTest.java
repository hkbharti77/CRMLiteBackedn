package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.whatsapp.WhatsAppCallWebhookEnvelope;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppPhoneNumberConfigRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceSessionRepository;
import com.chatcrmlite.backend.services.storage.CloudinaryStorageService;
import com.chatcrmlite.backend.services.voice.WhatsAppWebRtcMediaGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutomaticCallRecordingLifecycleTest {

    @Mock
    private WhatsAppCallSessionRepository callSessionRepository;
    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;
    @Mock
    private WhatsAppPhoneNumberConfigRepository phoneConfigRepository;
    @Mock
    private InboundCallPolicyService inboundCallPolicyService;
    @Mock
    private OutboundCallPermissionService outboundCallPermissionService;
    @Mock
    private MetaWhatsAppClient metaWhatsAppClient;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate redisTemplate;
    @Mock
    private WhatsAppWebRtcMediaGateway mediaGateway;
    @Mock
    private CloudinaryStorageService cloudinaryStorageService;
    @Mock
    private VoiceSessionRepository voiceSessionRepository;

    private WhatsAppCallingAgentService callingService;

    private final UUID tenantId = UUID.randomUUID();
    private final String phoneNumberId = "phone_rec_test_123";
    private final String wabaId = "waba_rec_456";
    private final String callId = "wa_call_auto_rec_999";
    private final String userWaId = "919876543210";

    @BeforeEach
    void setUp() {
        callingService = new WhatsAppCallingAgentService(
                callSessionRepository,
                whatsappConfigRepository,
                phoneConfigRepository,
                inboundCallPolicyService,
                outboundCallPermissionService,
                metaWhatsAppClient,
                redisTemplate,
                mediaGateway,
                cloudinaryStorageService,
                voiceSessionRepository
        );
        ReflectionTestUtils.setField(callingService, "sdpTtlSeconds", 300L);
    }

    private WhatsAppCallWebhookEnvelope createTerminatedEnvelope(String cId) {
        WhatsAppCallWebhookEnvelope.CallEvent event = new WhatsAppCallWebhookEnvelope.CallEvent(
                cId,
                userWaId,
                phoneNumberId,
                "TERMINATED",
                "USER_INITIATED",
                Instant.now().getEpochSecond(),
                null,
                null,
                null,
                15,
                null,
                null,
                null,
                null
        );

        WhatsAppCallWebhookEnvelope.Metadata metadata = new WhatsAppCallWebhookEnvelope.Metadata("+1234567890", phoneNumberId);
        WhatsAppCallWebhookEnvelope.Value value = new WhatsAppCallWebhookEnvelope.Value(
                "whatsapp",
                metadata,
                List.of(event),
                null
        );

        WhatsAppCallWebhookEnvelope.Change change = new WhatsAppCallWebhookEnvelope.Change("calls", value);
        WhatsAppCallWebhookEnvelope.Entry entry = new WhatsAppCallWebhookEnvelope.Entry(wabaId, List.of(change));
        return new WhatsAppCallWebhookEnvelope("whatsapp_business_account", List.of(entry));
    }

    @Test
    @DisplayName("Completed call triggers automatic audio assembly, WAV formatting, and Cloudinary upload")
    void testCallTerminated_AutomaticUploadSuccess() throws Exception {
        byte[] mockPcmAudio = new byte[9600]; // 100ms 48kHz mono 16-bit PCM
        for (int i = 0; i < mockPcmAudio.length; i++) mockPcmAudio[i] = (byte) (i % 127);

        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId))
                .thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .wabaId(wabaId)
                .phoneNumberId(phoneNumberId)
                .callId(callId)
                .direction("USER_INITIATED")
                .fromWaId(userWaId)
                .toWaId(phoneNumberId)
                .state("ACTIVE")
                .connectedAt(Instant.now().minusSeconds(15))
                .recordingStatus("NOT_AVAILABLE")
                .build();
        session.setTenantId(tenantId);

        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId))
                .thenReturn(Optional.of(session));

        when(mediaGateway.getRecordedAudio(callId)).thenReturn(mockPcmAudio);
        when(cloudinaryStorageService.isConfigured()).thenReturn(true);
        when(cloudinaryStorageService.uploadCallRecording(eq(tenantId), eq(callId), any(byte[].class)))
                .thenReturn(new CloudinaryStorageService.AudioUploadResult(
                        "https://res.cloudinary.com/test/call_auto.mp3",
                        "tenants/" + tenantId + "/whatsapp_recordings/call_auto",
                        "mp3",
                        15,
                        9644L
                ));

        callingService.processWebhookEnvelope(createTerminatedEnvelope(callId));

        ArgumentCaptor<byte[]> wavCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(cloudinaryStorageService, timeout(2000).times(1))
                .uploadCallRecording(eq(tenantId), eq(callId), wavCaptor.capture());

        // Validate audio format correctness (WAV 44-byte RIFF header, 48000Hz, 16-bit mono)
        byte[] uploadedWav = wavCaptor.getValue();
        assertEquals(9644, uploadedWav.length, "WAV length must be 44-byte header + 9600 bytes PCM");
        assertEquals("RIFF", new String(uploadedWav, 0, 4));
        assertEquals("WAVE", new String(uploadedWav, 8, 4));
        assertEquals("fmt ", new String(uploadedWav, 12, 4));

        ByteBuffer bb = ByteBuffer.wrap(uploadedWav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, bb.getShort(20), "AudioFormat must be 1 (PCM)");
        assertEquals(1, bb.getShort(22), "Channels must be 1 (Mono)");
        assertEquals(48000, bb.getInt(24), "SampleRate must be 48,000 Hz");
        assertEquals(16, bb.getShort(34), "BitsPerSample must be 16-bit");
        assertEquals("data", new String(uploadedWav, 36, 4));
        assertEquals(9600, bb.getInt(40), "Subchunk2Size must equal exact PCM data length");

        ArgumentCaptor<WhatsAppCallSession> captor = ArgumentCaptor.forClass(WhatsAppCallSession.class);
        verify(callSessionRepository, atLeastOnce()).save(captor.capture());

        WhatsAppCallSession lastSaved = captor.getValue();
        assertEquals("TERMINATED", lastSaved.getState());
        assertEquals("AVAILABLE", lastSaved.getRecordingStatus());
        assertEquals("https://res.cloudinary.com/test/call_auto.mp3", lastSaved.getRecordingUrl());
        assertEquals(15, lastSaved.getRecordingDurationSeconds());
        assertEquals(9644L, lastSaved.getRecordingSizeBytes());
    }

    @Test
    @DisplayName("Terminated call with no audio marked NOT_AVAILABLE without invoking Cloudinary")
    void testCallTerminated_NoAudioCaptured_StatusNotAvailable() throws Exception {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId))
                .thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .wabaId(wabaId)
                .phoneNumberId(phoneNumberId)
                .callId(callId)
                .direction("USER_INITIATED")
                .fromWaId(userWaId)
                .toWaId(phoneNumberId)
                .state("ACTIVE")
                .recordingStatus("NOT_AVAILABLE")
                .build();
        session.setTenantId(tenantId);

        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId))
                .thenReturn(Optional.of(session));

        when(mediaGateway.getRecordedAudio(callId)).thenReturn(new byte[0]);

        callingService.processWebhookEnvelope(createTerminatedEnvelope(callId));

        verify(cloudinaryStorageService, never()).uploadCallRecording(any(), any(), any());

        ArgumentCaptor<WhatsAppCallSession> captor = ArgumentCaptor.forClass(WhatsAppCallSession.class);
        verify(callSessionRepository, atLeastOnce()).save(captor.capture());

        WhatsAppCallSession lastSaved = captor.getValue();
        assertEquals("NOT_AVAILABLE", lastSaved.getRecordingStatus());
    }

    @Test
    @DisplayName("Transient upload failure automatically retries and succeeds on second attempt")
    void testRetryWithExponentialBackoff_RecoversOnSecondAttempt() throws Exception {
        byte[] mockPcmAudio = new byte[4800];
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId))
                .thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .wabaId(wabaId)
                .phoneNumberId(phoneNumberId)
                .callId(callId)
                .direction("USER_INITIATED")
                .fromWaId(userWaId)
                .toWaId(phoneNumberId)
                .state("ACTIVE")
                .recordingStatus("NOT_AVAILABLE")
                .build();
        session.setTenantId(tenantId);

        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId))
                .thenReturn(Optional.of(session));

        when(mediaGateway.getRecordedAudio(callId)).thenReturn(mockPcmAudio);
        when(cloudinaryStorageService.isConfigured()).thenReturn(true);
        // Attempt 1 fails, Attempt 2 succeeds
        when(cloudinaryStorageService.uploadCallRecording(eq(tenantId), eq(callId), any(byte[].class)))
                .thenThrow(new RuntimeException("Cloudinary socket timeout"))
                .thenReturn(new CloudinaryStorageService.AudioUploadResult(
                        "https://res.cloudinary.com/test/recovered.mp3",
                        "tenants/" + tenantId + "/whatsapp_recordings/recovered",
                        "mp3",
                        8,
                        4844L
                ));

        callingService.processWebhookEnvelope(createTerminatedEnvelope(callId));

        // Wait for retry attempt 2 (after 500ms backoff)
        verify(cloudinaryStorageService, timeout(3000).times(2))
                .uploadCallRecording(eq(tenantId), eq(callId), any(byte[].class));

        ArgumentCaptor<WhatsAppCallSession> captor = ArgumentCaptor.forClass(WhatsAppCallSession.class);
        verify(callSessionRepository, timeout(3000).atLeastOnce()).save(captor.capture());

        WhatsAppCallSession lastSaved = captor.getValue();
        assertEquals("AVAILABLE", lastSaved.getRecordingStatus());
        assertEquals("https://res.cloudinary.com/test/recovered.mp3", lastSaved.getRecordingUrl());
    }

    @Test
    @DisplayName("Exhaustion of bounded retries marks recordingStatus to FAILED and caches audio for manual recovery")
    void testCallTerminated_CloudinaryUploadThrowsException_StatusFailedAndCached() throws Exception {
        byte[] mockPcmAudio = new byte[9600];
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId))
                .thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .wabaId(wabaId)
                .phoneNumberId(phoneNumberId)
                .callId(callId)
                .direction("USER_INITIATED")
                .fromWaId(userWaId)
                .toWaId(phoneNumberId)
                .state("ACTIVE")
                .recordingStatus("NOT_AVAILABLE")
                .build();
        session.setTenantId(tenantId);

        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId))
                .thenReturn(Optional.of(session));

        when(mediaGateway.getRecordedAudio(callId)).thenReturn(mockPcmAudio);
        when(cloudinaryStorageService.isConfigured()).thenReturn(true);
        when(cloudinaryStorageService.uploadCallRecording(eq(tenantId), eq(callId), any(byte[].class)))
                .thenThrow(new RuntimeException("Cloudinary persistent 500"));

        callingService.processWebhookEnvelope(createTerminatedEnvelope(callId));

        // Verifies exactly 3 bounded attempts were made
        verify(cloudinaryStorageService, timeout(4000).times(3))
                .uploadCallRecording(eq(tenantId), eq(callId), any(byte[].class));

        ArgumentCaptor<WhatsAppCallSession> captor = ArgumentCaptor.forClass(WhatsAppCallSession.class);
        verify(callSessionRepository, timeout(4000).atLeast(2)).save(captor.capture());

        WhatsAppCallSession lastSaved = captor.getValue();
        assertEquals("FAILED", lastSaved.getRecordingStatus());
        assertEquals("TERMINATED", lastSaved.getState());

        // Audio must be cached in memory for manual retry
        byte[] cachedWav = callingService.getFailedRecordingAudio(callId);
        assertNotNull(cachedWav, "Failed upload audio must be cached for retry");
        assertEquals(9644, cachedWav.length);
    }

    @Test
    @DisplayName("Manual call termination triggers automatic recording finalization and upload")
    void testManualTermination_AutomaticUploadTriggered() throws Exception {
        byte[] mockPcmAudio = new byte[4800];
        WhatsAppConfig config = WhatsAppConfig.builder()
                .phoneNumberId(phoneNumberId)
                .accessToken("EAAG_TOKEN")
                .build();

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .wabaId(wabaId)
                .phoneNumberId(phoneNumberId)
                .callId(callId)
                .direction("BUSINESS_INITIATED")
                .toWaId(userWaId)
                .state("ACTIVE")
                .connectedAt(Instant.now().minusSeconds(10))
                .recordingStatus("NOT_AVAILABLE")
                .build();
        session.setTenantId(tenantId);

        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId))
                .thenReturn(Optional.of(session));
        when(whatsappConfigRepository.findByPhoneNumberId(phoneNumberId))
                .thenReturn(Optional.of(config));
        when(mediaGateway.getRecordedAudio(callId)).thenReturn(mockPcmAudio);
        when(cloudinaryStorageService.isConfigured()).thenReturn(true);
        when(cloudinaryStorageService.uploadCallRecording(eq(tenantId), eq(callId), any(byte[].class)))
                .thenReturn(new CloudinaryStorageService.AudioUploadResult(
                        "https://res.cloudinary.com/test/manual_term.mp3",
                        "tenants/" + tenantId + "/whatsapp_recordings/manual_term",
                        "mp3",
                        10,
                        4844L
                ));

        callingService.terminateActiveCall(tenantId, callId);

        verify(metaWhatsAppClient, times(1)).terminateCall(phoneNumberId, "EAAG_TOKEN", callId);
        verify(cloudinaryStorageService, timeout(2000).times(1))
                .uploadCallRecording(eq(tenantId), eq(callId), any(byte[].class));

        ArgumentCaptor<WhatsAppCallSession> captor = ArgumentCaptor.forClass(WhatsAppCallSession.class);
        verify(callSessionRepository, atLeastOnce()).save(captor.capture());

        WhatsAppCallSession lastSaved = captor.getValue();
        assertEquals("TERMINATED", lastSaved.getState());
        assertEquals("AVAILABLE", lastSaved.getRecordingStatus());
        assertEquals("https://res.cloudinary.com/test/manual_term.mp3", lastSaved.getRecordingUrl());
    }

    @Test
    @DisplayName("Prevent duplicate uploads when recording is already available")
    void testDuplicateTermination_PreventDuplicateUpload() throws Exception {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId))
                .thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .wabaId(wabaId)
                .phoneNumberId(phoneNumberId)
                .callId(callId)
                .direction("USER_INITIATED")
                .fromWaId(userWaId)
                .toWaId(phoneNumberId)
                .state("TERMINATED")
                .recordingStatus("AVAILABLE")
                .recordingUrl("https://res.cloudinary.com/test/existing.mp3")
                .build();
        session.setTenantId(tenantId);

        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId))
                .thenReturn(Optional.of(session));

        callingService.processWebhookEnvelope(createTerminatedEnvelope(callId));

        // Cloudinary upload should NOT be invoked
        verify(cloudinaryStorageService, never()).uploadCallRecording(any(), any(), any());
    }

    @Test
    @DisplayName("Durable Spool: Recovers pending/failed recordings after process restart with tenant isolation")
    void testDurableSpool_RecoversAfterProcessRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        ReflectionTestUtils.setField(callingService, "spoolDirPath", tempDir.toString());

        byte[] fakeWavAudio = new byte[]{0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45};
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        String testCallId = "wa_call_spool_test_123";

        // 1. Spool recording for Tenant A
        callingService.saveToSpool(tenantA, testCallId, fakeWavAudio);

        // 2. Clear in-memory map to simulate complete JVM process restart
        @SuppressWarnings("unchecked")
        java.util.Map<String, byte[]> inMemMap = (java.util.Map<String, byte[]>) ReflectionTestUtils.getField(callingService, "failedUploadAudios");
        assertNotNull(inMemMap);
        inMemMap.clear();
        assertEquals(0, inMemMap.size(), "In-memory cache must be empty after restart");

        // 3. Trigger startup recovery event
        callingService.recoverSpoolOnStartup();

        // 4. In-memory map should now have the recovered audio
        assertEquals(1, inMemMap.size(), "Startup recovery must repopulate failedUploadAudios");

        // 5. Tenant A can retrieve the recovered audio
        byte[] recoveredA = callingService.getFailedRecordingAudio(tenantA, testCallId);
        assertNotNull(recoveredA);
        assertArrayEquals(fakeWavAudio, recoveredA);

        // 6. Tenant B cannot access Tenant A's spooled recording (Tenant Isolation)
        byte[] recoveredB = callingService.getFailedRecordingAudio(tenantB, testCallId);
        assertNull(recoveredB, "Cross-tenant access to spooled recording must return null");

        // 7. Cleanup spool on successful upload
        callingService.deleteFromSpool(tenantA, testCallId);
        assertNull(callingService.readFromSpool(tenantA, testCallId), "Spool file must be removed after successful upload");
    }
}
