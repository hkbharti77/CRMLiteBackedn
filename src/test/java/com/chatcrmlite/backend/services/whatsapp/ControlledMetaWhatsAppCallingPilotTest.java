package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.whatsapp.WhatsAppCallWebhookEnvelope;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppPhoneNumberConfig;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppPhoneNumberConfigRepository;
import com.chatcrmlite.backend.services.voice.WhatsAppVoiceCallBridgeService;
import com.chatcrmlite.backend.services.voice.WhatsAppWebRtcMediaGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 3: Controlled Meta WhatsApp Calling Pilot Test Suite
 *
 * Validates real-world calling flows, lifecycle transitions, Meta Cloud API interactions,
 * status webhooks (RINGING/REJECTED/MISSED/FAILED), error diagnostics (138021 remapping),
 * and audio pipeline integration without requiring live network access to Meta.
 */
@ExtendWith(MockitoExtension.class)
class ControlledMetaWhatsAppCallingPilotTest {

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

    private WhatsAppCallingAgentService callingAgentService;

    private final UUID tenantId = UUID.randomUUID();
    private final String phoneNumberId = "phone_meta_pilot_01";
    private final String wabaId = "waba_meta_pilot_01";
    private final String callId = "wa_call_pilot_101";
    private final String userWaId = "919876543210";
    private final String metaAccessToken = "EAAG_PILOT_ACCESS_TOKEN";
    private WhatsAppConfig config;

    @BeforeEach
    void setUp() {
        callingAgentService = new WhatsAppCallingAgentService(
                callSessionRepository,
                whatsappConfigRepository,
                phoneConfigRepository,
                inboundCallPolicyService,
                outboundCallPermissionService,
                metaWhatsAppClient,
                redisTemplate,
                mediaGateway
        );
        ReflectionTestUtils.setField(callingAgentService, "sdpTtlSeconds", 300L);

        config = WhatsAppConfig.builder()
                .wabaId(wabaId)
                .phoneNumberId(phoneNumberId)
                .accessToken(metaAccessToken)
                .build();
        com.chatcrmlite.backend.models.Tenant tenant = new com.chatcrmlite.backend.models.Tenant();
        tenant.setId(tenantId);
        config.setTenant(tenant);
    }

    private WhatsAppCallWebhookEnvelope createEnvelope(
            List<WhatsAppCallWebhookEnvelope.CallEvent> events,
            List<WhatsAppCallWebhookEnvelope.CallStatus> statuses
    ) {
        var metadata = new WhatsAppCallWebhookEnvelope.Metadata("+15551234567", phoneNumberId);
        var value = new WhatsAppCallWebhookEnvelope.Value("whatsapp", metadata, events, statuses);
        var change = new WhatsAppCallWebhookEnvelope.Change("calls", value);
        var entry = new WhatsAppCallWebhookEnvelope.Entry(wabaId, List.of(change));
        return new WhatsAppCallWebhookEnvelope("whatsapp_business_account", List.of(entry));
    }

    // ──────────────── 1. Outbound Calling Lifecycle ────────────────

    @Test
    @DisplayName("Pilot Flow 1: Outbound Call - Full Lifecycle (Initiate -> Ringing -> Connect Answer -> Active -> Hangup)")
    void testOutboundCall_FullLifecycle() {
        // Step 1: Initiate outbound call
        when(outboundCallPermissionService.canInitiateOutboundCall(tenantId, phoneNumberId, userWaId)).thenReturn(true);
        when(phoneConfigRepository.findByTenantIdAndPhoneNumberId(tenantId, phoneNumberId)).thenReturn(Optional.empty());
        when(callSessionRepository.countActiveCallsForPhone(tenantId, phoneNumberId)).thenReturn(0L);
        when(whatsappConfigRepository.findByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(config));
        when(mediaGateway.createMediaSession(anyString(), eq(tenantId), eq(userWaId), isNull())).thenReturn("v=0\r\nsdp-offer-local");
        when(callSessionRepository.save(any(WhatsAppCallSession.class))).thenAnswer(inv -> inv.getArgument(0));

        String initiatedCallId = callingAgentService.initiateOutboundCall(tenantId, phoneNumberId, userWaId, "biz_data_123");
        assertNotNull(initiatedCallId);
        assertTrue(initiatedCallId.startsWith("wa_call_"));

        verify(mediaGateway).createMediaSession(eq(initiatedCallId), eq(tenantId), eq(userWaId), isNull());
        verify(metaWhatsAppClient).initiateBusinessCall(eq(phoneNumberId), eq(metaAccessToken), eq(userWaId), eq("v=0\r\nsdp-offer-local"), eq("biz_data_123"));
        verify(outboundCallPermissionService).recordLimitEvent(eq(tenantId), eq(phoneNumberId), eq(userWaId), eq("CALL_INITIATED"), eq(initiatedCallId));

        // Step 2: Meta sends RINGING status
        WhatsAppCallSession activeSession = WhatsAppCallSession.builder()
                .callId(initiatedCallId)
                .phoneNumberId(phoneNumberId)
                .toWaId(userWaId)
                .state("OFFER_SENT")
                .direction("BUSINESS_INITIATED")
                .build();
        activeSession.setTenantId(tenantId);
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, initiatedCallId)).thenReturn(Optional.of(activeSession));

        var ringingStatus = new WhatsAppCallWebhookEnvelope.CallStatus(
                initiatedCallId, System.currentTimeMillis() / 1000, userWaId, "RINGING", null, null
        );
        callingAgentService.processWebhookEnvelope(createEnvelope(null, List.of(ringingStatus)));
        assertEquals("RINGING", activeSession.getState());

        // Step 3: Meta sends CONNECT with remote SDP answer
        var sessionPayload = new WhatsAppCallWebhookEnvelope.CallSession("v=0\r\nsdp-answer-meta", "audio/rtp");
        var connectEvent = new WhatsAppCallWebhookEnvelope.CallEvent(
                initiatedCallId, phoneNumberId, userWaId, "CONNECT", "BUSINESS_INITIATED",
                System.currentTimeMillis() / 1000, sessionPayload, null, null, null, null, null, null, null
        );
        callingAgentService.processWebhookEnvelope(createEnvelope(List.of(connectEvent), null));

        assertEquals("ACTIVE", activeSession.getState());
        assertNotNull(activeSession.getConnectedAt());
        verify(mediaGateway).startOutboundMedia(initiatedCallId);
        verify(outboundCallPermissionService).recordLimitEvent(eq(tenantId), eq(phoneNumberId), eq(userWaId), eq("CALL_CONNECTED"), eq(initiatedCallId));

        // Step 4: Normal Hangup by user/agent
        callingAgentService.terminateActiveCall(tenantId, initiatedCallId);
        assertEquals("TERMINATED", activeSession.getState());
        assertNotNull(activeSession.getEndedAt());
        verify(metaWhatsAppClient).terminateCall(phoneNumberId, metaAccessToken, initiatedCallId);
        verify(mediaGateway).terminateSession(initiatedCallId);
    }

    // ──────────────── 2. Out-of-Band Status Handling ────────────────

    @Test
    @DisplayName("Pilot Flow 2: Outbound Call - Callee REJECTED with Meta error cleans up media & records limit")
    void testOutboundCall_CalleeRejected_CleansUpAndRecordsEvent() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .toWaId(userWaId)
                .state("OFFER_SENT")
                .direction("BUSINESS_INITIATED")
                .build();
        session.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(session));

        var error = new WhatsAppCallWebhookEnvelope.CallError(138010, "CALL_REJECTED", "User declined the call");
        var rejectedStatus = new WhatsAppCallWebhookEnvelope.CallStatus(
                callId, System.currentTimeMillis() / 1000, userWaId, "REJECTED", List.of(error), null
        );

        callingAgentService.processWebhookEnvelope(createEnvelope(null, List.of(rejectedStatus)));

        assertEquals("REJECTED", session.getState());
        assertEquals(Integer.valueOf(138010), session.getMetaErrorCode());
        assertEquals("CALL_REJECTED", session.getMetaErrorTitle());
        assertNotNull(session.getEndedAt());
        verify(mediaGateway).terminateSession(callId);
        verify(outboundCallPermissionService).recordLimitEvent(tenantId, phoneNumberId, userWaId, "CALL_REJECTED", callId);
    }

    @Test
    @DisplayName("Pilot Flow 3: Outbound Call - MISSED/TIMEOUT cleans up media and updates state")
    void testOutboundCall_MissedStatus_CleansUpSession() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .toWaId(userWaId)
                .state("RINGING")
                .direction("BUSINESS_INITIATED")
                .build();
        session.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(session));

        var missedStatus = new WhatsAppCallWebhookEnvelope.CallStatus(
                callId, System.currentTimeMillis() / 1000, userWaId, "MISSED", null, null
        );

        callingAgentService.processWebhookEnvelope(createEnvelope(null, List.of(missedStatus)));

        assertEquals("MISSED", session.getState());
        assertNotNull(session.getEndedAt());
        verify(mediaGateway).terminateSession(callId);
    }

    // ──────────────── 3. Authoritative Meta Duration ────────────────

    @Test
    @DisplayName("Pilot Flow 4: Call Termination captures authoritative Meta duration from webhook")
    void testCallTerminated_UsesMetaAuthoritativeDuration() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .toWaId(userWaId)
                .state("ACTIVE")
                .connectedAt(Instant.now().minusSeconds(125))
                .build();
        session.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(session));

        // Meta reports exact duration = 120 seconds
        var termEvent = new WhatsAppCallWebhookEnvelope.CallEvent(
                callId, phoneNumberId, userWaId, "TERMINATED", "BUSINESS_INITIATED",
                System.currentTimeMillis() / 1000, null,
                System.currentTimeMillis() / 1000 - 120, System.currentTimeMillis() / 1000, 120,
                null, null, null, null
        );

        callingAgentService.processWebhookEnvelope(createEnvelope(List.of(termEvent), null));

        assertEquals("TERMINATED", session.getState());
        assertEquals(Integer.valueOf(120), session.getDurationSeconds());
    }

    // ──────────────── 4. Error Remapping (Meta 138021) ────────────────

    @Test
    @DisplayName("Pilot Flow 5: Meta 138021 MEDIA_RECEIVE_TIMEOUT remapped to DTLS_FAILED when DTLS failed locally")
    void testMetaError138021_RemappedToDtlsFailure() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .toWaId(userWaId)
                .state("ACTIVE")
                .build();
        session.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(session));

        when(mediaGateway.resolveMediaFailureReason(callId)).thenReturn("DTLS_FAILED: Handshake timed out");
        when(mediaGateway.getMediaState(callId)).thenReturn(WhatsAppWebRtcMediaGateway.CallMediaState.ICE_CONNECTED);

        var error = new WhatsAppCallWebhookEnvelope.CallError(138021, "MEDIA_RECEIVE_TIMEOUT", "Timed out waiting for media");
        var termEvent = new WhatsAppCallWebhookEnvelope.CallEvent(
                callId, phoneNumberId, userWaId, "TERMINATED", "USER_INITIATED",
                System.currentTimeMillis() / 1000, null, null, null, null, List.of(error), null, null, null
        );

        callingAgentService.processWebhookEnvelope(createEnvelope(List.of(termEvent), null));

        assertEquals("TERMINATED", session.getState());
        assertEquals(Integer.valueOf(138021), session.getMetaErrorCode());
        assertEquals("DTLS_FAILED", session.getMetaErrorTitle());
        assertTrue(session.getMetaErrorMessage().contains("DTLS_FAILED: Handshake timed out"));
    }

    @Test
    @DisplayName("Pilot Flow 6: Meta 138021 MEDIA_RECEIVE_TIMEOUT remapped to RTP_NOT_RECEIVED when media never arrived")
    void testMetaError138021_RemappedToRtpNotReceived() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .toWaId(userWaId)
                .state("ACTIVE")
                .build();
        session.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(session));

        when(mediaGateway.resolveMediaFailureReason(callId)).thenReturn("RTP_NOT_RECEIVED");
        when(mediaGateway.getMediaState(callId)).thenReturn(WhatsAppWebRtcMediaGateway.CallMediaState.SRTP_READY);

        var error = new WhatsAppCallWebhookEnvelope.CallError(138021, "MEDIA_RECEIVE_TIMEOUT", "Timed out waiting for media");
        var termEvent = new WhatsAppCallWebhookEnvelope.CallEvent(
                callId, phoneNumberId, userWaId, "TERMINATED", "USER_INITIATED",
                System.currentTimeMillis() / 1000, null, null, null, null, List.of(error), null, null, null
        );

        callingAgentService.processWebhookEnvelope(createEnvelope(List.of(termEvent), null));

        assertEquals("TERMINATED", session.getState());
        assertEquals(Integer.valueOf(138021), session.getMetaErrorCode());
        assertEquals("RTP_NOT_RECEIVED", session.getMetaErrorTitle());
    }

    // ──────────────── 5. Upstream Meta Graph API Errors ────────────────

    @Test
    @DisplayName("Pilot Flow 7: Upstream Meta initiation error sets FAILED state and cleans up Redis SDP")
    void testOutboundCall_UpstreamMetaError_SetsFailedState() {
        when(outboundCallPermissionService.canInitiateOutboundCall(tenantId, phoneNumberId, userWaId)).thenReturn(true);
        when(phoneConfigRepository.findByTenantIdAndPhoneNumberId(tenantId, phoneNumberId)).thenReturn(Optional.empty());
        when(callSessionRepository.countActiveCallsForPhone(tenantId, phoneNumberId)).thenReturn(0L);
        when(whatsappConfigRepository.findByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(config));
        when(mediaGateway.createMediaSession(anyString(), eq(tenantId), eq(userWaId), isNull())).thenReturn("v=0\r\nsdp-offer-local");
        when(callSessionRepository.save(any(WhatsAppCallSession.class))).thenAnswer(inv -> inv.getArgument(0));

        // Meta Graph API returns HTTP 502 / Connection reset
        doThrow(new RuntimeException("Connection reset by peer"))
                .when(metaWhatsAppClient).initiateBusinessCall(anyString(), anyString(), anyString(), anyString(), any());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                callingAgentService.initiateOutboundCall(tenantId, phoneNumberId, userWaId, null)
        );

        assertTrue(ex.getMessage().contains("Failed to initiate outbound call with Meta"));
        verify(mediaGateway).terminateSession(anyString());
        verify(callSessionRepository, atLeastOnce()).save(argThat(s ->
                "FAILED".equals(s.getState()) &&
                "INITIATION".equals(s.getFailureStage()) &&
                "CALL_SETUP_FAILED".equals(s.getMetaErrorTitle())
        ));
    }
}
