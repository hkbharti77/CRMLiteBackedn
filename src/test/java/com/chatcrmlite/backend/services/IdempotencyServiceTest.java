package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.exceptions.InfrastructureException;
import com.chatcrmlite.backend.models.ProcessedMessage;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ProcessedMessageRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private ProcessedMessageRepository processedMessageRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    /**
     * TransactionTemplate must be mocked and configured to execute the lambda inline.
     * Without this, the transactionTemplate is null and all DB-layer tests fail with NPE.
     */
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private IdempotencyService idempotencyService;

    private UUID tenantId;
    private UUID userId;
    private String messageId;
    private User owner;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        userId   = UUID.randomUUID();
        messageId = "wamid.123456";
        owner = new User();
        owner.setId(userId);

        // Make transactionTemplate.executeWithoutResult() invoke the lambda synchronously.
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback =
                    invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    // ────────────────────────────────────────────────
    // Happy path
    // ────────────────────────────────────────────────

    @Test
    void testSuccessfulProcessing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("CLAIMED"), any(Duration.class))).thenReturn(true);

        // New flow: look up userId from tenantId, then getReferenceById(userId)
        when(userRepository.findFirstUserIdByTenantId(tenantId)).thenReturn(Optional.of(userId));
        when(userRepository.getReferenceById(userId)).thenReturn(owner);

        boolean result = idempotencyService.markAsProcessing(messageId, tenantId);

        assertTrue(result);
        verify(processedMessageRepository, times(1)).saveAndFlush(any(ProcessedMessage.class));
        // Should never call existsById anymore
        verify(userRepository, never()).existsById(any());
    }

    // ────────────────────────────────────────────────
    // Redis fast-path duplicate
    // ────────────────────────────────────────────────

    @Test
    void testDuplicateRequest_CaughtByRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("CLAIMED"), any(Duration.class))).thenReturn(false);

        boolean result = idempotencyService.markAsProcessing(messageId, tenantId);

        assertFalse(result);
        verify(userRepository, never()).getReferenceById(any());
        verify(processedMessageRepository, never()).saveAndFlush(any());
    }

    // ────────────────────────────────────────────────
    // Database-layer duplicate (UNIQUE constraint)
    // ────────────────────────────────────────────────

    @Test
    void testDuplicateRequest_CaughtByDatabase() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("CLAIMED"), any(Duration.class))).thenReturn(true);

        when(userRepository.findFirstUserIdByTenantId(tenantId)).thenReturn(Optional.of(userId));
        when(userRepository.getReferenceById(userId)).thenReturn(owner);
        when(processedMessageRepository.saveAndFlush(any(ProcessedMessage.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate key"));

        boolean result = idempotencyService.markAsProcessing(messageId, tenantId);

        // Because we validated the tenant before insert, a DataIntegrityViolation == duplicate message
        assertFalse(result);
        // existsById must NOT be called anymore (the old broken heuristic)
        verify(userRepository, never()).existsById(any());
    }

    // ────────────────────────────────────────────────
    // Redis falls back to DB on unexpected error
    // ────────────────────────────────────────────────

    @Test
    void testRedisUnavailable_ShouldFallbackToDatabase() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Unknown redis error"));

        when(userRepository.findFirstUserIdByTenantId(tenantId)).thenReturn(Optional.of(userId));
        when(userRepository.getReferenceById(userId)).thenReturn(owner);

        boolean result = idempotencyService.markAsProcessing(messageId, tenantId);

        assertTrue(result);
        verify(processedMessageRepository, times(1)).saveAndFlush(any(ProcessedMessage.class));
    }

    // ────────────────────────────────────────────────
    // Redis infrastructure failure (connection-level) → InfrastructureException
    // ────────────────────────────────────────────────

    @Test
    void testRedisInfrastructureFailure_ShouldThrowException() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Redis down"));

        InfrastructureException exception = assertThrows(InfrastructureException.class, () ->
                idempotencyService.markAsProcessing(messageId, tenantId));

        assertTrue(exception.getMessage().contains("Redis failure"));
        verify(userRepository, never()).getReferenceById(any());
    }

    // ────────────────────────────────────────────────
    // DB timeout → InfrastructureException
    // ────────────────────────────────────────────────

    @Test
    void testDatabaseTimeout_ShouldThrowException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("CLAIMED"), any(Duration.class))).thenReturn(true);

        when(userRepository.findFirstUserIdByTenantId(tenantId)).thenReturn(Optional.of(userId));
        when(userRepository.getReferenceById(userId)).thenReturn(owner);
        when(processedMessageRepository.saveAndFlush(any(ProcessedMessage.class)))
                .thenThrow(new QueryTimeoutException("DB timeout"));

        InfrastructureException exception = assertThrows(InfrastructureException.class, () ->
                idempotencyService.markAsProcessing(messageId, tenantId));

        assertTrue(exception.getMessage().contains("Database failure"));
    }

    // ────────────────────────────────────────────────
    // Tenant not found → IllegalArgumentException (fail fast before DB insert)
    // ────────────────────────────────────────────────

    @Test
    void testValidationFailure_TenantNotFound() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("CLAIMED"), any(Duration.class))).thenReturn(true);

        // Tenant has no users → empty Optional
        when(userRepository.findFirstUserIdByTenantId(tenantId)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                idempotencyService.markAsProcessing(messageId, tenantId));

        assertTrue(exception.getMessage().contains("Tenant not found"));
        // No DB insert should ever be attempted for an unknown tenant
        verify(processedMessageRepository, never()).saveAndFlush(any());
    }

    // ────────────────────────────────────────────────
    // Purge scheduled job (unchanged logic)
    // ────────────────────────────────────────────────

    @Test
    void testPurgeOldRecords_BatchedDeletion() {
        java.util.List<Long> batch1 = new java.util.ArrayList<>();
        java.util.List<Long> batch2 = new java.util.ArrayList<>();
        java.util.List<Long> batch3 = new java.util.ArrayList<>();
        for (long i = 1;     i <= 5000;  i++) batch1.add(i);
        for (long i = 5001;  i <= 10000; i++) batch2.add(i);
        for (long i = 10001; i <= 12000; i++) batch3.add(i);

        when(processedMessageRepository.findIdsOlderThan(any(), any()))
                .thenReturn(batch1)
                .thenReturn(batch2)
                .thenReturn(batch3);

        when(processedMessageRepository.deleteByIdIn(batch1)).thenReturn(5000);
        when(processedMessageRepository.deleteByIdIn(batch2)).thenReturn(5000);
        when(processedMessageRepository.deleteByIdIn(batch3)).thenReturn(2000);

        idempotencyService.purgeOldRecordsInternal();

        verify(processedMessageRepository, times(3)).findIdsOlderThan(any(), any());
        verify(processedMessageRepository, times(1)).deleteByIdIn(batch1);
        verify(processedMessageRepository, times(1)).deleteByIdIn(batch2);
        verify(processedMessageRepository, times(1)).deleteByIdIn(batch3);
    }
}
