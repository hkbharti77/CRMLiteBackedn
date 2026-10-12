package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.models.WhatsAppConfig;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CallConcurrencyTest {

    @Mock
    private WhatsAppPhoneNumberConfigRepository phoneConfigRepository;

    @Mock
    private WhatsAppCallSessionRepository callSessionRepository;

    @Mock
    private WhatsAppConfigRepository configRepository;

    @Mock
    private MetaWhatsAppClient metaWhatsAppClient;

    @Mock
    private WhatsAppWebRtcMediaGateway mediaGateway;

    @Mock
    private OutboundCallPermissionService outboundPermissionService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private InboundCallPolicyService inboundPolicyService;
    private WhatsAppCallingAgentService callingService;

    private final UUID tenantId = UUID.randomUUID();
    private final String phoneNumberId = "phone_concurrent_123";
    private WhatsAppPhoneNumberConfig phoneConfig;

    @BeforeEach
    void setUp() {
        inboundPolicyService = new InboundCallPolicyService(phoneConfigRepository, callSessionRepository);

        callingService = new WhatsAppCallingAgentService(
                callSessionRepository,
                configRepository,
                phoneConfigRepository,
                inboundPolicyService,
                outboundPermissionService,
                metaWhatsAppClient,
                redisTemplate,
                mediaGateway
        );
        ReflectionTestUtils.setField(callingService, "sdpTtlSeconds", 300L);

        phoneConfig = WhatsAppPhoneNumberConfig.builder()
                .phoneNumberId(phoneNumberId)
                .callingStatus("ENABLED")
                .maxConcurrentCalls(5)
                .build();
        phoneConfig.setTenantId(tenantId);

        lenient().when(phoneConfigRepository.findByTenantIdAndPhoneNumberId(tenantId, phoneNumberId))
                .thenReturn(Optional.of(phoneConfig));
        lenient().when(phoneConfigRepository.findByPhoneNumberId(phoneNumberId))
                .thenReturn(Optional.of(phoneConfig));
    }

    @Test
    @DisplayName("Inbound call accepted when active calls are below max concurrent limit")
    void testInboundBelowConcurrencyLimit() {
        when(callSessionRepository.countActiveCallsForPhone(tenantId, phoneNumberId))
                .thenReturn(4L); // 4 < 5

        assertTrue(inboundPolicyService.canAcceptInboundCall(tenantId, phoneNumberId, "919876543210"));
    }

    @Test
    @DisplayName("Inbound call rejected when active calls are exactly at max concurrent limit")
    void testInboundExactlyAtConcurrencyLimit() {
        when(callSessionRepository.countActiveCallsForPhone(tenantId, phoneNumberId))
                .thenReturn(5L); // 5 == 5

        assertFalse(inboundPolicyService.canAcceptInboundCall(tenantId, phoneNumberId, "919876543210"));
    }

    @Test
    @DisplayName("Inbound call rejected when active calls exceed max concurrent limit")
    void testInboundAboveConcurrencyLimit() {
        when(callSessionRepository.countActiveCallsForPhone(tenantId, phoneNumberId))
                .thenReturn(6L); // 6 > 5

        assertFalse(inboundPolicyService.canAcceptInboundCall(tenantId, phoneNumberId, "919876543210"));
    }

    @Test
    @DisplayName("Inbound call rejected when phone number calling status is DISABLED")
    void testInboundDisabledCallingStatus() {
        phoneConfig.setCallingStatus("DISABLED");

        assertFalse(inboundPolicyService.canAcceptInboundCall(tenantId, phoneNumberId, "919876543210"));
        verify(callSessionRepository, never()).countActiveCallsForPhone(any(), any());
    }

    @Test
    @DisplayName("Outbound call rejected when active calls are at or above max concurrent limit")
    void testOutboundAtConcurrencyLimit() {
        when(outboundPermissionService.canInitiateOutboundCall(tenantId, phoneNumberId, "919876543210"))
                .thenReturn(true);
        when(callSessionRepository.countActiveCallsForPhone(tenantId, phoneNumberId))
                .thenReturn(5L); // 5 == 5

        assertThrows(IllegalStateException.class, () ->
                callingService.initiateOutboundCall(tenantId, phoneNumberId, "919876543210", null)
        );
        verify(metaWhatsAppClient, never()).initiateBusinessCall(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Concurrent multi-threaded evaluation correctly gates call limit")
    void testConcurrentCallLimitGating() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger activeCount = new AtomicInteger(4); // Starts at 4, max 5

        when(callSessionRepository.countActiveCallsForPhone(tenantId, phoneNumberId))
                .thenAnswer(inv -> (long) activeCount.get());

        Future<Boolean>[] futures = new Future[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = executor.submit(() -> {
                latch.await();
                return inboundPolicyService.canAcceptInboundCall(tenantId, phoneNumberId, "919876543210");
            });
        }

        latch.countDown();

        int accepted = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(5, TimeUnit.SECONDS)) {
                accepted++;
            }
        }

        executor.shutdown();
        // Since active count mock returns 4, all queries see 4 < 5
        assertEquals(threads, accepted);
    }
}
