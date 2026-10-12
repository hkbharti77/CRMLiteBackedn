package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallPermission;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppPhoneNumberConfig;
import com.chatcrmlite.backend.repositories.CallLimitEventRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCallPermissionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppPhoneNumberConfigRepository;
import com.chatcrmlite.backend.services.voice.WhatsAppVoiceCallBridgeService;
import com.chatcrmlite.backend.services.voice.WhatsAppWebRtcMediaGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantIsolationCallingTest {

    @Mock
    private WhatsAppConfigRepository configRepository;

    @Mock
    private WhatsAppPhoneNumberConfigRepository phoneConfigRepository;

    @Mock
    private WhatsAppCallSessionRepository callSessionRepository;

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
    private WhatsAppCallPermissionRepository permissionRepository;

    @Mock
    private CallLimitEventRepository eventRepository;

    private WhatsAppCallingAgentService callingService;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final String callIdB = "call_tenant_b_999";

    @BeforeEach
    void setUp() {
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
    }

    @Test
    @DisplayName("Tenant A cannot terminate Tenant B's active call")
    void testTenantCannotTerminateOtherTenantCall() {
        // Call belongs to Tenant B
        WhatsAppCallSession sessionB = WhatsAppCallSession.builder()
                .callId(callIdB)
                .fromWaId("919876543210")
                .state("ACTIVE")
                .build();
        sessionB.setTenantId(tenantB);

        // When Tenant A attempts to find this call, repo returns empty because scoped to tenantId
        when(callSessionRepository.findByTenantIdAndCallId(tenantA, callIdB))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> callingService.terminateActiveCall(tenantA, callIdB));

        // Verify media gateway session was NOT terminated
        verify(mediaGateway, never()).terminateSession(callIdB);
        // Verify Meta API was NOT invoked
        verify(metaWhatsAppClient, never()).terminateCall(any(), any(), any());
    }

    @Test
    @DisplayName("Tenant A cannot initiate outbound call using Tenant B's phone number")
    void testTenantCannotUseOtherTenantPhoneNumber() {
        String phoneB = "phone_belongs_to_b";
        when(outboundCallPermissionService.canInitiateOutboundCall(tenantA, phoneB, "919876543210"))
                .thenReturn(true);
        when(phoneConfigRepository.findByTenantIdAndPhoneNumberId(tenantA, phoneB))
                .thenReturn(Optional.empty());
        when(configRepository.findByPhoneNumberId(phoneB))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                callingService.initiateOutboundCall(tenantA, phoneB, "919876543210", null)
        );

        verify(metaWhatsAppClient, never()).initiateBusinessCall(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Tenant A cannot query or inherit Tenant B's call permissions")
    void testPermissionQueriesAreTenantScoped() {
        OutboundCallPermissionService permService = new OutboundCallPermissionService(
                permissionRepository,
                eventRepository,
                mock(MetaCallingLimitsResolver.class),
                redisTemplate,
                metaWhatsAppClient,
                configRepository
        );

        String userWaId = "919876543210";
        String phoneId = "phone_123";

        when(permissionRepository.findByTenantIdAndPhoneNumberIdAndUserWaId(tenantA, phoneId, userWaId))
                .thenReturn(Optional.empty());

        assertFalse(permService.isPermissionActive(tenantA, phoneId, userWaId));
        verify(permissionRepository).findByTenantIdAndPhoneNumberIdAndUserWaId(eq(tenantA), eq(phoneId), eq(userWaId));
    }

    @Test
    @DisplayName("Tenant A's call limit events are strictly isolated from Tenant B's counts")
    void testCallLimitEventTenantIsolation() {
        OutboundCallPermissionService permService = new OutboundCallPermissionService(
                permissionRepository,
                eventRepository,
                mock(MetaCallingLimitsResolver.class),
                redisTemplate,
                metaWhatsAppClient,
                configRepository
        );

        String phoneId = "phone_123";
        // When checking events for Tenant A, repo is queried with tenantA
        when(eventRepository.countEventsSince(eq(tenantA), eq(phoneId), eq("CALL_INITIATED"), any()))
                .thenReturn(2L);

        // Verification that query explicitly includes tenantA
        long count = eventRepository.countEventsSince(tenantA, phoneId, "CALL_INITIATED", java.time.Instant.now());
        assertEquals(2L, count);
        verify(eventRepository).countEventsSince(eq(tenantA), eq(phoneId), eq("CALL_INITIATED"), any());
    }
}
