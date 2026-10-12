package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.models.whatsapp.CallLimitEvent;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallPermission;
import com.chatcrmlite.backend.repositories.CallLimitEventRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCallPermissionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboundCallPermissionTest {

    @Mock
    private WhatsAppCallPermissionRepository permissionRepository;

    @Mock
    private CallLimitEventRepository eventRepository;

    @Mock
    private MetaCallingLimitsResolver limitsResolver;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private MetaWhatsAppClient metaWhatsAppClient;

    @Mock
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Mock
    private ListOperations<String, String> listOperations;

    private OutboundCallPermissionService permissionService;

    private final UUID tenantId = UUID.randomUUID();
    private final String phoneNumberId = "phone_num_123";
    private final String userWaId = "919876543210";

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);

        permissionService = new OutboundCallPermissionService(
                permissionRepository,
                eventRepository,
                limitsResolver,
                redisTemplate,
                metaWhatsAppClient,
                whatsappConfigRepository
        );

        lenient().when(limitsResolver.resolve(eq(tenantId), eq(phoneNumberId)))
                .thenReturn(new MetaCallingLimits(10000, 100, 1, 2));
    }

    @Test
    @DisplayName("Permission missing returns false")
    void testPermissionMissing() {
        when(permissionRepository.findByTenantIdAndPhoneNumberIdAndUserWaId(tenantId, phoneNumberId, userWaId))
                .thenReturn(Optional.empty());

        assertFalse(permissionService.isPermissionActive(tenantId, phoneNumberId, userWaId));
        assertFalse(permissionService.canInitiateOutboundCall(tenantId, phoneNumberId, userWaId));
    }

    @Test
    @DisplayName("Expired temporary permission returns false")
    void testPermissionExpired() {
        WhatsAppCallPermission perm = WhatsAppCallPermission.builder()
                .phoneNumberId(phoneNumberId)
                .userWaId(userWaId)
                .status("GRANTED_TEMPORARY")
                .expiresAt(Instant.now().minus(Duration.ofHours(1))) // expired
                .build();
        perm.setTenantId(tenantId);

        when(permissionRepository.findByTenantIdAndPhoneNumberIdAndUserWaId(tenantId, phoneNumberId, userWaId))
                .thenReturn(Optional.of(perm));

        assertFalse(permissionService.isPermissionActive(tenantId, phoneNumberId, userWaId));
    }

    @Test
    @DisplayName("Temporary permission with null expiresAt expires after 72 hours from grantedAt")
    void testDefault72HourExpiration() {
        // Granted 73 hours ago, expiresAt is null
        WhatsAppCallPermission perm = WhatsAppCallPermission.builder()
                .phoneNumberId(phoneNumberId)
                .userWaId(userWaId)
                .status("GRANTED_TEMPORARY")
                .grantedAt(Instant.now().minus(Duration.ofHours(73)))
                .expiresAt(null)
                .build();
        perm.setTenantId(tenantId);

        when(permissionRepository.findByTenantIdAndPhoneNumberIdAndUserWaId(tenantId, phoneNumberId, userWaId))
                .thenReturn(Optional.of(perm));

        assertFalse(permissionService.isPermissionActive(tenantId, phoneNumberId, userWaId));
    }

    @Test
    @DisplayName("Valid temporary permission within 72 hours returns true")
    void testValidTemporaryPermission() {
        WhatsAppCallPermission perm = WhatsAppCallPermission.builder()
                .phoneNumberId(phoneNumberId)
                .userWaId(userWaId)
                .status("GRANTED_TEMPORARY")
                .expiresAt(Instant.now().plus(Duration.ofHours(48)))
                .build();
        perm.setTenantId(tenantId);

        when(permissionRepository.findByTenantIdAndPhoneNumberIdAndUserWaId(tenantId, phoneNumberId, userWaId))
                .thenReturn(Optional.of(perm));

        assertTrue(permissionService.isPermissionActive(tenantId, phoneNumberId, userWaId));
    }

    @Test
    @DisplayName("Permanent permission returns true")
    void testPermanentPermission() {
        WhatsAppCallPermission perm = WhatsAppCallPermission.builder()
                .phoneNumberId(phoneNumberId)
                .userWaId(userWaId)
                .status("GRANTED_PERMANENT")
                .build();
        perm.setTenantId(tenantId);

        when(permissionRepository.findByTenantIdAndPhoneNumberIdAndUserWaId(tenantId, phoneNumberId, userWaId))
                .thenReturn(Optional.of(perm));

        assertTrue(permissionService.isPermissionActive(tenantId, phoneNumberId, userWaId));
    }

    @Test
    @DisplayName("24-hour initiation limit (10,000) reached blocks outbound calls")
    void testInitiationLimitReached() {
        when(eventRepository.countEventsSince(eq(tenantId), eq(phoneNumberId), eq("CALL_INITIATED"), any()))
                .thenReturn(10000L); // at limit

        assertFalse(permissionService.canInitiateOutboundCall(tenantId, phoneNumberId, userWaId));
        verify(permissionRepository, never()).findByTenantIdAndPhoneNumberIdAndUserWaId(any(), any(), any());
    }

    @Test
    @DisplayName("24-hour connected call limit (100) reached blocks outbound calls")
    void testConnectedCallLimitReached() {
        when(eventRepository.countEventsSince(eq(tenantId), eq(phoneNumberId), eq("CALL_INITIATED"), any()))
                .thenReturn(50L);
        when(eventRepository.countEventsSince(eq(tenantId), eq(phoneNumberId), eq("CALL_CONNECTED"), any()))
                .thenReturn(100L); // at limit

        assertFalse(permissionService.canInitiateOutboundCall(tenantId, phoneNumberId, userWaId));
    }

    @Test
    @DisplayName("24-hour permission request limit (1 per 24h) blocks spamming user with permission prompts")
    void testPermissionRequestLimitReached() {
        when(eventRepository.countUserEventsSince(eq(tenantId), eq(phoneNumberId), eq(userWaId), eq("PERMISSION_REQUEST"), any()))
                .thenReturn(1L); // already requested once in 24h

        boolean allowed = permissionService.requestCallPermission(tenantId, phoneNumberId, userWaId, "Please give call permission");
        assertFalse(allowed);
        verify(metaWhatsAppClient, never()).sendCallPermissionRequest(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Webhook permission reconciliation persists status and safely evicts Redis cache")
    void testWebhookReconciliationSafeRedisEviction() {
        when(permissionRepository.findByTenantIdAndPhoneNumberIdAndUserWaId(tenantId, phoneNumberId, userWaId))
                .thenReturn(Optional.empty());
        when(permissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Simulate Redis throwing connection error during cache eviction
        doThrow(new RuntimeException("Redis connection refused")).when(redisTemplate).delete(anyString());

        assertDoesNotThrow(() -> {
            permissionService.reconcilePermissionFromWebhook(
                    tenantId, phoneNumberId, userWaId, "GRANTED_TEMPORARY", "VOICE", false, "USER_CLICK", null
            );
        });

        verify(permissionRepository, times(1)).save(argThat(p ->
                "GRANTED_TEMPORARY".equals(p.getStatus()) && p.getExpiresAt() != null
        ));
    }

    @Test
    @DisplayName("Record limit event handles Redis failure gracefully without failing PostgreSQL transaction")
    void testRecordLimitEventRedisResilience() {
        when(redisTemplate.opsForList()).thenThrow(new RuntimeException("Redis cluster down"));

        assertDoesNotThrow(() -> {
            permissionService.recordLimitEvent(tenantId, phoneNumberId, userWaId, "CALL_INITIATED", "call_123");
        });

        verify(eventRepository, times(1)).save(argThat(e ->
                "CALL_INITIATED".equals(e.getEventType()) && "call_123".equals(e.getCallId())
        ));
    }
}
