package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.whatsapp.WhatsAppCallWebhookEnvelope;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppPhoneNumberConfig;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppPhoneNumberConfigRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CallStateMachineTest {

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

    private WhatsAppCallingAgentService callingService;

    private final UUID tenantId = UUID.randomUUID();
    private final String phoneNumberId = "phone_123456";
    private final String wabaId = "waba_789";
    private final String callId = "wa_call_test_001";
    private final String userWaId = "919876543210";
    private final String testToken = "EAAG_TEST_TOKEN";
    private WhatsAppConfig config;

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
                mediaGateway
        );
        ReflectionTestUtils.setField(callingService, "sdpTtlSeconds", 300L);

        config = WhatsAppConfig.builder()
                .wabaId(wabaId)
                .phoneNumberId(phoneNumberId)
                .accessToken(testToken)
                .build();
        com.chatcrmlite.backend.models.Tenant tenant = new com.chatcrmlite.backend.models.Tenant();
        tenant.setId(tenantId);
        config.setTenant(tenant);
    }

    private WhatsAppCallWebhookEnvelope createInboundConnectEnvelope(String cId, String sdp) {
        var session = new WhatsAppCallWebhookEnvelope.CallSession(sdp, "audio/rtp");
        var event = new WhatsAppCallWebhookEnvelope.CallEvent(
                cId, userWaId, phoneNumberId, "CONNECT", "USER_INITIATED",
                System.currentTimeMillis() / 1000, session, null, null, null, null, null, null, null
        );
        var metadata = new WhatsAppCallWebhookEnvelope.Metadata("+1234567890", phoneNumberId);
        var value = new WhatsAppCallWebhookEnvelope.Value("whatsapp", metadata, List.of(event), null);
        var change = new WhatsAppCallWebhookEnvelope.Change("calls", value);
        var entry = new WhatsAppCallWebhookEnvelope.Entry(wabaId, List.of(change));
        return new WhatsAppCallWebhookEnvelope("whatsapp_business_account", List.of(entry));
    }

    private WhatsAppCallWebhookEnvelope createTerminatedEnvelope(String cId, List<WhatsAppCallWebhookEnvelope.CallError> errors) {
        var event = new WhatsAppCallWebhookEnvelope.CallEvent(
                cId, userWaId, phoneNumberId, "TERMINATED", "USER_INITIATED",
                System.currentTimeMillis() / 1000, null, System.currentTimeMillis() / 1000 - 30, System.currentTimeMillis() / 1000, 30, errors, null, null, null
        );
        var metadata = new WhatsAppCallWebhookEnvelope.Metadata("+1234567890", phoneNumberId);
        var value = new WhatsAppCallWebhookEnvelope.Value("whatsapp", metadata, List.of(event), null);
        var change = new WhatsAppCallWebhookEnvelope.Change("calls", value);
        var entry = new WhatsAppCallWebhookEnvelope.Entry(wabaId, List.of(change));
        return new WhatsAppCallWebhookEnvelope("whatsapp_business_account", List.of(entry));
    }

    @Test
    @DisplayName("Inbound Call: Happy path state transitions RECEIVED -> PRE_ACCEPTING -> MEDIA_NEGOTIATING -> ACCEPTING -> ACTIVE")
    void testInboundCallHappyPathStateTransitions() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.empty());
        when(inboundCallPolicyService.canAcceptInboundCall(tenantId, phoneNumberId, userWaId)).thenReturn(true);
        when(whatsappConfigRepository.findByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(config));
        when(mediaGateway.createMediaSession(eq(callId), eq(tenantId), eq(userWaId), any())).thenReturn("v=0\r\nsdp-answer");
        when(callSessionRepository.save(any(WhatsAppCallSession.class))).thenAnswer(inv -> inv.getArgument(0));

        callingService.processWebhookEnvelope(createInboundConnectEnvelope(callId, "v=0\r\nsdp-offer"));

        verify(mediaGateway).createMediaSession(eq(callId), eq(tenantId), eq(userWaId), eq("v=0\r\nsdp-offer"));
        verify(metaWhatsAppClient).preAcceptCall(phoneNumberId, testToken, callId, "v=0\r\nsdp-answer");
        verify(metaWhatsAppClient).acceptIncomingCall(phoneNumberId, testToken, callId);
        verify(mediaGateway).startOutboundMedia(callId);
        verify(callSessionRepository, atLeast(2)).save(argThat(s ->
                s.getCallId().equals(callId) && ("ACTIVE".equals(s.getState()) || "PRE_ACCEPTING".equals(s.getState()))
        ));
    }

    @Test
    @DisplayName("Inbound Call: Policy rejection invokes Meta reject and saves REJECTED state")
    void testInboundCallPolicyRejection() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.empty());
        when(inboundCallPolicyService.canAcceptInboundCall(tenantId, phoneNumberId, userWaId)).thenReturn(false);
        when(whatsappConfigRepository.findByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(config));
        when(callSessionRepository.save(any(WhatsAppCallSession.class))).thenAnswer(inv -> inv.getArgument(0));

        callingService.processWebhookEnvelope(createInboundConnectEnvelope(callId, "v=0\r\nsdp-offer"));

        verify(metaWhatsAppClient).rejectIncomingCall(phoneNumberId, testToken, callId, "USER_BUSY");
        verify(mediaGateway, never()).createMediaSession(any(), any(), any(), any());
        verify(callSessionRepository).save(argThat(s -> "REJECTED".equals(s.getState())));
    }

    @Test
    @DisplayName("Inbound Call: Meta API setup failure transitions session to FAILED and cleans up resources")
    void testInboundCallSetupFailureCleansUpResources() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.empty());
        when(inboundCallPolicyService.canAcceptInboundCall(tenantId, phoneNumberId, userWaId)).thenReturn(true);
        when(whatsappConfigRepository.findByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(config));
        when(mediaGateway.createMediaSession(eq(callId), eq(tenantId), eq(userWaId), any())).thenReturn("v=0\r\nsdp-answer");
        when(callSessionRepository.save(any(WhatsAppCallSession.class))).thenAnswer(inv -> inv.getArgument(0));

        // Simulate Meta Graph API failure (e.g. 500 error)
        doThrow(new RuntimeException("Meta pre-accept HTTP 500"))
                .when(metaWhatsAppClient).preAcceptCall(anyString(), anyString(), anyString(), anyString());

        callingService.processWebhookEnvelope(createInboundConnectEnvelope(callId, "v=0\r\nsdp-offer"));

        verify(mediaGateway).terminateSession(callId);
        verify(callSessionRepository, atLeastOnce()).save(argThat(s ->
                "FAILED".equals(s.getState()) && "CALL_SETUP_FAILED".equals(s.getMetaErrorTitle())
        ));
    }

    @Test
    @DisplayName("Inbound Call: Duplicate CONNECT for already ACTIVE call is safely ignored")
    void testDuplicateConnectOnActiveCallIsIgnored() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));

        WhatsAppCallSession activeSession = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .state("ACTIVE")
                .direction("USER_INITIATED")
                .build();
        activeSession.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(activeSession));

        callingService.processWebhookEnvelope(createInboundConnectEnvelope(callId, "v=0\r\nsdp-offer"));

        // Must not re-run setup or call Meta again
        verify(inboundCallPolicyService, never()).canAcceptInboundCall(any(), any(), any());
        verify(metaWhatsAppClient, never()).preAcceptCall(any(), any(), any(), any());
        verify(metaWhatsAppClient, never()).acceptIncomingCall(any(), any(), any());
        verify(mediaGateway, never()).createMediaSession(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Inbound Call: Late CONNECT after TERMINATED call never resurrects the call")
    void testLateConnectNeverResurrectsTerminatedCall() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));

        WhatsAppCallSession terminatedSession = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .state("TERMINATED")
                .direction("USER_INITIATED")
                .endedAt(Instant.now().minusSeconds(10))
                .build();
        terminatedSession.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(terminatedSession));

        callingService.processWebhookEnvelope(createInboundConnectEnvelope(callId, "v=0\r\nsdp-offer"));

        verify(metaWhatsAppClient, never()).preAcceptCall(any(), any(), any(), any());
        verify(metaWhatsAppClient, never()).acceptIncomingCall(any(), any(), any());
        verify(mediaGateway, never()).startOutboundMedia(any());
        assertEquals("TERMINATED", terminatedSession.getState());
    }

    @Test
    @DisplayName("Outbound Call: Late CONNECT answer after call is TERMINATED does not reopen call")
    void testOutboundLateConnectDoesNotReopenTerminatedCall() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));

        WhatsAppCallSession terminatedSession = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .state("TERMINATED")
                .direction("BUSINESS_INITIATED")
                .endedAt(Instant.now().minusSeconds(5))
                .build();
        terminatedSession.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(terminatedSession));

        // Create outbound answer event
        var session = new WhatsAppCallWebhookEnvelope.CallSession("v=0\r\nsdp-answer", "audio/rtp");
        var event = new WhatsAppCallWebhookEnvelope.CallEvent(
                callId, phoneNumberId, userWaId, "CONNECT", "BUSINESS_INITIATED",
                System.currentTimeMillis() / 1000, session, null, null, null, null, null, null, null
        );
        var metadata = new WhatsAppCallWebhookEnvelope.Metadata("+1234567890", phoneNumberId);
        var value = new WhatsAppCallWebhookEnvelope.Value("whatsapp", metadata, List.of(event), null);
        var change = new WhatsAppCallWebhookEnvelope.Change("calls", value);
        var entry = new WhatsAppCallWebhookEnvelope.Entry(wabaId, List.of(change));
        var envelope = new WhatsAppCallWebhookEnvelope("whatsapp_business_account", List.of(entry));

        callingService.processWebhookEnvelope(envelope);

        verify(mediaGateway, never()).startOutboundMedia(any());
        assertEquals("TERMINATED", terminatedSession.getState());
    }

    @Test
    @DisplayName("Call Termination: Idempotent termination does not re-invoke Meta API")
    void testIdempotentTerminationDoesNotReinvokeMeta() {
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .state("ACTIVE")
                .connectedAt(Instant.now().minusSeconds(45))
                .build();
        session.setTenantId(tenantId);

        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(session));
        when(whatsappConfigRepository.findByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(config));

        // First termination
        callingService.terminateActiveCall(tenantId, callId);
        verify(metaWhatsAppClient, times(1)).terminateCall(phoneNumberId, testToken, callId);
        verify(mediaGateway, times(1)).terminateSession(callId);
        assertEquals("TERMINATED", session.getState());

        // Second termination call (idempotent)
        callingService.terminateActiveCall(tenantId, callId);
        // Meta API should NOT be invoked a second time
        verify(metaWhatsAppClient, times(1)).terminateCall(phoneNumberId, testToken, callId);
        verify(mediaGateway, times(1)).terminateSession(callId);
    }

    @Test
    @DisplayName("Remote Termination: Webhook terminates session with Meta error mapping and duration")
    void testRemoteTerminationWithMetaErrorMapping() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .state("ACTIVE")
                .connectedAt(Instant.now().minusSeconds(60))
                .build();
        session.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(session));

        var callError = new WhatsAppCallWebhookEnvelope.CallError(138006, "CALL_PERMISSION_DENIED", "No call permission");
        callingService.processWebhookEnvelope(createTerminatedEnvelope(callId, List.of(callError)));

        verify(mediaGateway).terminateSession(callId);
        verify(callSessionRepository).save(argThat(s ->
                "TERMINATED".equals(s.getState()) &&
                Integer.valueOf(138006).equals(s.getMetaErrorCode()) &&
                "CALL_PERMISSION_DENIED".equals(s.getMetaErrorTitle())
        ));
    }
}
