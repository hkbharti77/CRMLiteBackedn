package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.whatsapp.WhatsAppCallWebhookEnvelope;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppPhoneNumberConfigRepository;
import com.chatcrmlite.backend.services.voice.WhatsAppWebRtcMediaGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookIdempotencyTest {

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID tenantId = UUID.randomUUID();
    private final String phoneNumberId = "phone_999888";
    private final String wabaId = "waba_777";
    private final String callId = "wa_call_idem_001";
    private final String userWaId = "919999999999";
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

    @Test
    @DisplayName("Webhook: Null or empty envelope is processed gracefully without exceptions")
    void testNullOrEmptyEnvelopeHandledGracefully() {
        assertDoesNotThrow(() -> callingService.processWebhookEnvelope(null));
        assertDoesNotThrow(() -> callingService.processWebhookEnvelope(new WhatsAppCallWebhookEnvelope(null, null)));
        assertDoesNotThrow(() -> callingService.processWebhookEnvelope(new WhatsAppCallWebhookEnvelope("whatsapp_business_account", Collections.emptyList())));
    }

    @Test
    @DisplayName("Webhook: Envelope with missing calls and statuses array is ignored safely")
    void testMissingCallsFieldIsIgnoredSafely() {
        var metadata = new WhatsAppCallWebhookEnvelope.Metadata("+1234567890", phoneNumberId);
        var value = new WhatsAppCallWebhookEnvelope.Value("whatsapp", metadata, null, null);
        var change = new WhatsAppCallWebhookEnvelope.Change("calls", value);
        var entry = new WhatsAppCallWebhookEnvelope.Entry(wabaId, List.of(change));
        var envelope = new WhatsAppCallWebhookEnvelope("whatsapp_business_account", List.of(entry));

        assertDoesNotThrow(() -> callingService.processWebhookEnvelope(envelope));
        verify(mediaGateway, never()).createMediaSession(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Webhook: CallEvent with missing or blank call ID is dropped safely")
    void testMissingCallIdIsDroppedSafely() {
        var event = new WhatsAppCallWebhookEnvelope.CallEvent(
                "", userWaId, phoneNumberId, "CONNECT", "USER_INITIATED",
                System.currentTimeMillis() / 1000, null, null, null, null, null, null, null, null
        );
        var metadata = new WhatsAppCallWebhookEnvelope.Metadata("+1234567890", phoneNumberId);
        var value = new WhatsAppCallWebhookEnvelope.Value("whatsapp", metadata, List.of(event), null);
        var change = new WhatsAppCallWebhookEnvelope.Change("calls", value);
        var entry = new WhatsAppCallWebhookEnvelope.Entry(wabaId, List.of(change));
        var envelope = new WhatsAppCallWebhookEnvelope("whatsapp_business_account", List.of(entry));

        assertDoesNotThrow(() -> callingService.processWebhookEnvelope(envelope));
        verify(callSessionRepository, never()).findByTenantIdAndCallId(any(), any());
        verify(redisTemplate.opsForValue(), never()).set(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Webhook: Unknown event type is safely ignored without throwing exceptions")
    void testUnknownEventSafelyIgnored() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));

        var event = new WhatsAppCallWebhookEnvelope.CallEvent(
                callId, userWaId, phoneNumberId, "UNKNOWN_EVENT_PING", "USER_INITIATED",
                System.currentTimeMillis() / 1000, null, null, null, null, null, null, null, null
        );
        var metadata = new WhatsAppCallWebhookEnvelope.Metadata("+1234567890", phoneNumberId);
        var value = new WhatsAppCallWebhookEnvelope.Value("whatsapp", metadata, List.of(event), null);
        var change = new WhatsAppCallWebhookEnvelope.Change("calls", value);
        var entry = new WhatsAppCallWebhookEnvelope.Entry(wabaId, List.of(change));
        var envelope = new WhatsAppCallWebhookEnvelope("whatsapp_business_account", List.of(entry));

        assertDoesNotThrow(() -> callingService.processWebhookEnvelope(envelope));
        verify(mediaGateway, never()).createMediaSession(any(), any(), any(), any());
        verify(mediaGateway, never()).terminateSession(any());
    }

    @Test
    @DisplayName("Webhook: Duplicate webhook envelope delivers exactly identical actions once (idempotent)")
    void testDuplicateWebhookEnvelopeIsIdempotent() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));
        when(whatsappConfigRepository.findByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(config));
        when(inboundCallPolicyService.canAcceptInboundCall(tenantId, phoneNumberId, userWaId)).thenReturn(true);
        when(mediaGateway.createMediaSession(eq(callId), eq(tenantId), eq(userWaId), any())).thenReturn("v=0\r\nsdp-answer");

        WhatsAppCallSession createdSession = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .state("PRE_ACCEPTING")
                .build();
        createdSession.setTenantId(tenantId);

        when(callSessionRepository.save(any(WhatsAppCallSession.class))).thenAnswer(inv -> inv.getArgument(0));

        // First delivery: session not found initially
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.empty());

        var session = new WhatsAppCallWebhookEnvelope.CallSession("v=0\r\nsdp-offer", "audio/rtp");
        var event = new WhatsAppCallWebhookEnvelope.CallEvent(
                callId, userWaId, phoneNumberId, "CONNECT", "USER_INITIATED",
                System.currentTimeMillis() / 1000, session, null, null, null, null, null, null, null
        );
        var metadata = new WhatsAppCallWebhookEnvelope.Metadata("+1234567890", phoneNumberId);
        var value = new WhatsAppCallWebhookEnvelope.Value("whatsapp", metadata, List.of(event), null);
        var change = new WhatsAppCallWebhookEnvelope.Change("calls", value);
        var entry = new WhatsAppCallWebhookEnvelope.Entry(wabaId, List.of(change));
        var envelope = new WhatsAppCallWebhookEnvelope("whatsapp_business_account", List.of(entry));

        // Delivery 1
        callingService.processWebhookEnvelope(envelope);
        verify(metaWhatsAppClient, times(1)).preAcceptCall(eq(phoneNumberId), anyString(), eq(callId), anyString());
        verify(metaWhatsAppClient, times(1)).acceptIncomingCall(eq(phoneNumberId), anyString(), eq(callId));

        // Second delivery (simulating Redis redelivery or Meta retry while call is ACTIVE)
        WhatsAppCallSession activeSession = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .state("ACTIVE")
                .build();
        activeSession.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(activeSession));

        // Delivery 2
        callingService.processWebhookEnvelope(envelope);

        // Verification: Meta API must STILL have been called only once!
        verify(metaWhatsAppClient, times(1)).preAcceptCall(eq(phoneNumberId), anyString(), eq(callId), anyString());
        verify(metaWhatsAppClient, times(1)).acceptIncomingCall(eq(phoneNumberId), anyString(), eq(callId));
    }

    @Test
    @DisplayName("Webhook: Out-of-order events where TERMINATED arrives before delayed CONNECT")
    void testOutOfOrderEventsTerminatedBeforeConnect() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));

        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId(callId)
                .phoneNumberId(phoneNumberId)
                .state("TERMINATED")
                .endedAt(Instant.now().minusSeconds(10))
                .build();
        session.setTenantId(tenantId);
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.of(session));

        var event = new WhatsAppCallWebhookEnvelope.CallEvent(
                callId, userWaId, phoneNumberId, "CONNECT", "USER_INITIATED",
                System.currentTimeMillis() / 1000, null, null, null, null, null, null, null, null
        );
        var metadata = new WhatsAppCallWebhookEnvelope.Metadata("+1234567890", phoneNumberId);
        var value = new WhatsAppCallWebhookEnvelope.Value("whatsapp", metadata, List.of(event), null);
        var change = new WhatsAppCallWebhookEnvelope.Change("calls", value);
        var entry = new WhatsAppCallWebhookEnvelope.Entry(wabaId, List.of(change));
        var envelope = new WhatsAppCallWebhookEnvelope("whatsapp_business_account", List.of(entry));

        callingService.processWebhookEnvelope(envelope);

        // Delayed CONNECT must be ignored
        verify(metaWhatsAppClient, never()).preAcceptCall(any(), any(), any(), any());
        verify(metaWhatsAppClient, never()).acceptIncomingCall(any(), any(), any());
        assertEquals("TERMINATED", session.getState());
    }

    @Test
    @DisplayName("Webhook: JSON deserialization resilience for official Meta envelope payload")
    void testJsonDeserializationResilience() throws Exception {
        String json = """
        {
          "object": "whatsapp_business_account",
          "entry": [
            {
              "id": "123456789",
              "changes": [
                {
                  "field": "calls",
                  "value": {
                    "messaging_product": "whatsapp",
                    "metadata": {
                      "display_phone_number": "+16505551234",
                      "phone_number_id": "phone_123"
                    },
                    "calls": [
                      {
                        "id": "wa_call_abc123",
                        "from": "16505559876",
                        "to": "16505551234",
                        "event": "CONNECT",
                        "direction": "USER_INITIATED",
                        "timestamp": 1727788800,
                        "session": {
                          "sdp": "v=0\\r\\no=- 123 2 IN IP4 127.0.0.1",
                          "media_type": "audio/rtp"
                        }
                      }
                    ]
                  }
                }
              ]
            }
          ]
        }
        """;

        WhatsAppCallWebhookEnvelope envelope = objectMapper.readValue(json, WhatsAppCallWebhookEnvelope.class);
        assertNotNull(envelope);
        assertNotNull(envelope.entry());
        assertEquals(1, envelope.entry().size());
        var call = envelope.entry().get(0).changes().get(0).value().calls().get(0);
        assertEquals("wa_call_abc123", call.id());
        assertEquals("CONNECT", call.event());
        assertEquals("USER_INITIATED", call.direction());
        assertNotNull(call.session());
        assertTrue(call.session().sdp().contains("v=0"));
    }
}
