package com.chatcrmlite.backend.services.visitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisitorQuotaServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private VisitorQuotaService visitorQuotaService;

    private UUID tenantId;
    private String visitorId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        visitorId = UUID.randomUUID().toString();
        ReflectionTestUtils.setField(visitorQuotaService, "defaultMaxDailyMessages", 15);
        ReflectionTestUtils.setField(visitorQuotaService, "defaultMaxDailyTokens", 3000);
        ReflectionTestUtils.setField(visitorQuotaService, "maxPromptLengthChars", 1000);
        ReflectionTestUtils.setField(visitorQuotaService, "maxOutputTokens", 450);
        visitorQuotaService.init();
        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("1. Rejects prompt exceeding max character ceiling")
    void testCheckAndReserveQuota_PromptTooLong() {
        String longPrompt = "a".repeat(1005);
        var result = visitorQuotaService.checkAndReserveQuota(tenantId, visitorId, 150, longPrompt);

        assertFalse(result.isAllowed());
        assertEquals(VisitorQuotaService.QuotaStatus.PROMPT_TOO_LONG, result.getStatus());
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("2. Successfully reserves quota when Lua returns 1")
    void testCheckAndReserveQuota_Success() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(), anyString(), anyString(), anyString()
        )).thenReturn(List.of(1L, "RESERVED", 1L, 150L));

        var result = visitorQuotaService.checkAndReserveQuota(tenantId, visitorId, 150, "Hello, can you help me?");

        assertTrue(result.isAllowed());
        assertEquals(VisitorQuotaService.QuotaStatus.ALLOWED, result.getStatus());
        assertEquals(1L, result.getCurrentMessages());
        assertEquals(150L, result.getCurrentTokens());
    }

    @Test
    @DisplayName("3. Blocks concurrent query when in-flight lock exists (Lua returns -1)")
    void testCheckAndReserveQuota_ConcurrentBlocked() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(), anyString(), anyString(), anyString()
        )).thenReturn(List.of(-1L, "CONCURRENT_REQUEST_BLOCKED", 1L, 150L));

        var result = visitorQuotaService.checkAndReserveQuota(tenantId, visitorId, 150, "Concurrent prompt");

        assertFalse(result.isAllowed());
        assertEquals(VisitorQuotaService.QuotaStatus.CONCURRENT_BLOCKED, result.getStatus());
    }

    @Test
    @DisplayName("4. Blocks request when message limit is exceeded (Lua returns 0 MESSAGE_LIMIT_EXCEEDED)")
    void testCheckAndReserveQuota_MessageLimitExceeded() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(), anyString(), anyString(), anyString()
        )).thenReturn(List.of(0L, "MESSAGE_LIMIT_EXCEEDED", 15L, 2200L));

        var result = visitorQuotaService.checkAndReserveQuota(tenantId, visitorId, 150, "16th question");

        assertFalse(result.isAllowed());
        assertEquals(VisitorQuotaService.QuotaStatus.MESSAGE_LIMIT_EXCEEDED, result.getStatus());
    }

    @Test
    @DisplayName("5. Blocks request when token budget is exceeded (Lua returns 0 TOKEN_LIMIT_EXCEEDED)")
    void testCheckAndReserveQuota_TokenLimitExceeded() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(), anyString(), anyString(), anyString()
        )).thenReturn(List.of(0L, "TOKEN_LIMIT_EXCEEDED", 8L, 2950L));

        var result = visitorQuotaService.checkAndReserveQuota(tenantId, visitorId, 150, "Heavy question");

        assertFalse(result.isAllowed());
        assertEquals(VisitorQuotaService.QuotaStatus.TOKEN_LIMIT_EXCEEDED, result.getStatus());
    }

    @Test
    @DisplayName("6. settleVisitorUsage deletes in-flight lock and adjusts token difference")
    void testSettleVisitorUsage() {
        visitorQuotaService.settleVisitorUsage(tenantId, visitorId, 150, 180);

        verify(stringRedisTemplate).delete("inflight:" + tenantId + ":" + visitorId);
        // Token difference = 180 - 150 = 30
        verify(hashOperations).increment(anyString(), eq("tokens"), eq(30L));
        verify(hashOperations).increment(anyString(), eq("actual_output_tokens"), eq(180L));
    }

    @Test
    @DisplayName("7. releaseVisitorReservation deletes in-flight lock and refunds reserved capacity")
    void testReleaseVisitorReservation() {
        when(hashOperations.increment(anyString(), eq("msgs"), eq(-1L))).thenReturn(0L);
        when(hashOperations.increment(anyString(), eq("tokens"), eq(-150L))).thenReturn(0L);

        visitorQuotaService.releaseVisitorReservation(tenantId, visitorId, 150);

        verify(stringRedisTemplate).delete("inflight:" + tenantId + ":" + visitorId);
        verify(hashOperations).increment(anyString(), eq("msgs"), eq(-1L));
        verify(hashOperations).increment(anyString(), eq("tokens"), eq(-150L));
    }
}
