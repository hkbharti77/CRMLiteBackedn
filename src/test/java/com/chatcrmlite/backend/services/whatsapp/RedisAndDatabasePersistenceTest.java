package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.whatsapp.WhatsAppCallWebhookEnvelope;
import com.chatcrmlite.backend.models.Tenant;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisAndDatabasePersistenceTest {

    @Mock
    private WhatsAppCallSessionRepository callSessionRepository;

    @Mock
    private WhatsAppConfigRepository configRepository;

    @Mock
    private WhatsAppPhoneNumberConfigRepository phoneConfigRepository;

    @Mock
    private InboundCallPolicyService inboundCallPolicyService;

    @Mock
    private OutboundCallPermissionService outboundCallPermissionService;

    @Mock
    private MetaWhatsAppClient metaWhatsAppClient;

    @Mock
    private WhatsAppWebRtcMediaGateway mediaGateway;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private WhatsAppCallingAgentService callingService;

    private final UUID tenantId = UUID.randomUUID();
    private final String phoneNumberId = "phone_db_test_123";
    private final String wabaId = "waba_db_test_123";
    private final String callId = "call_redis_db_01";
    private WhatsAppConfig config;
    private WhatsAppPhoneNumberConfig phoneConfig;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        callingService = new WhatsAppCallingAgentService(
                callSessionRepository,
                configRepository,
                phoneConfigRepository,
                inboundCallPolicyService,
                outboundCallPermissionService,
                metaWhatsAppClient,
                redisTemplate,
                mediaGateway
        );
        ReflectionTestUtils.setField(callingService, "sdpTtlSeconds", 300L);

        config = WhatsAppConfig.builder()
                .phoneNumberId(phoneNumberId)
                .wabaId(wabaId)
                .accessToken("test_token")
                .build();
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        config.setTenant(tenant);

        phoneConfig = WhatsAppPhoneNumberConfig.builder()
                .phoneNumberId(phoneNumberId)
                .callingStatus("ENABLED")
                .maxConcurrentCalls(5)
                .build();
        phoneConfig.setTenantId(tenantId);

        lenient().when(configRepository.findByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(config));
        lenient().when(configRepository.findTenantIdByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(tenantId));
        lenient().when(phoneConfigRepository.findByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(phoneConfig));
        lenient().when(inboundCallPolicyService.canAcceptInboundCall(any(), any(), any())).thenReturn(true);
    }

    private WhatsAppCallWebhookEnvelope createInboundConnectEnvelope(String cId, String sdp) {
        var session = new WhatsAppCallWebhookEnvelope.CallSession(sdp, "audio/rtp");
        var event = new WhatsAppCallWebhookEnvelope.CallEvent(
                cId, "919876543210", phoneNumberId, "CONNECT", "USER_INITIATED",
                System.currentTimeMillis() / 1000, session, null, null, null, null, null, null, null
        );
        var metadata = new WhatsAppCallWebhookEnvelope.Metadata("+1234567890", phoneNumberId);
        var value = new WhatsAppCallWebhookEnvelope.Value("whatsapp", metadata, List.of(event), null);
        var change = new WhatsAppCallWebhookEnvelope.Change("calls", value);
        var entry = new WhatsAppCallWebhookEnvelope.Entry(wabaId, List.of(change));
        return new WhatsAppCallWebhookEnvelope("whatsapp_business_account", List.of(entry));
    }

    @Test
    @DisplayName("Redis outage during SDP storage does not crash inbound call handling")
    void testRedisOutageDuringSdpStorageResilience() {
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.empty());
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaGateway.createMediaSession(eq(callId), eq(tenantId), any(), any()))
                .thenReturn("v=0\r\ns=Test\r\n");

        // Simulate Redis throwing connection failure
        doThrow(new RedisConnectionFailureException("Redis offline"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        var envelope = createInboundConnectEnvelope(callId, "v=0\r\n");

        assertDoesNotThrow(() -> callingService.processWebhookEnvelope(envelope));

        // Call session was still saved in PostgreSQL
        verify(callSessionRepository, atLeastOnce()).save(argThat(s ->
                callId.equals(s.getCallId()) && "ACTIVE".equals(s.getState())
        ));
    }

    @Test
    @DisplayName("Call termination persists TERMINATED state in PostgreSQL")
    void testCallTerminationPersistence() {
        WhatsAppCallSession activeSession = WhatsAppCallSession.builder()
                .callId(callId)
                .fromWaId("919876543210")
                .phoneNumberId(phoneNumberId)
                .state("ACTIVE")
                .build();
        activeSession.setTenantId(tenantId);

        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId))
                .thenReturn(Optional.of(activeSession));
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> callingService.terminateActiveCall(tenantId, callId));

        verify(callSessionRepository).save(argThat(s ->
                "TERMINATED".equals(s.getState())
                && s.getEndedAt() != null
        ));
    }

    @Test
    @DisplayName("Failed call setup marks database session as FAILED and cleans up resources")
    void testFailedCallSetupPersistenceAndCleanup() {
        when(callSessionRepository.findByTenantIdAndCallId(tenantId, callId)).thenReturn(Optional.empty());
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Media session creation throws unexpected exception
        when(mediaGateway.createMediaSession(eq(callId), eq(tenantId), any(), any()))
                .thenThrow(new RuntimeException("Socket bind failure"));

        var envelope = createInboundConnectEnvelope(callId, "v=0\r\n");

        callingService.processWebhookEnvelope(envelope);

        // Verify session marked FAILED in DB and media session terminated
        verify(callSessionRepository, atLeastOnce()).save(argThat(s ->
                callId.equals(s.getCallId()) && "FAILED".equals(s.getState())
        ));
        verify(mediaGateway).terminateSession(callId);
    }
}
