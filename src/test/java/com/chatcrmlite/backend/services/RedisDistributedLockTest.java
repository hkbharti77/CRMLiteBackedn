package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.services.workflow.WorkflowStateTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RedisDistributedLockTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisStateService redisStateService;
    private WorkflowStateTracker stateTracker;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        redisStateService = new RedisStateService(redisTemplate, stringRedisTemplate);
        stateTracker = new WorkflowStateTracker(redisStateService);
    }

    @Test
    @DisplayName("Worker A acquires lock with unique token and releases cleanly")
    void testLockAcquisitionAndRelease() {
        String lockKey = "workflow:lock:user:919876543210";
        String token = UUID.randomUUID().toString();

        when(valueOperations.setIfAbsent(eq(lockKey), eq(token), any(Duration.class))).thenReturn(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(token)))
                .thenReturn(1L);

        boolean acquired = redisStateService.tryLock(lockKey, token, Duration.ofMinutes(1));
        assertTrue(acquired);

        boolean released = redisStateService.unlock(lockKey, token);
        assertTrue(released);
    }

    @Test
    @DisplayName("Race condition prevention: Expired Worker A lock cannot release Worker B's active lock")
    void testExpiredLock_DoesNotReleaseAnotherWorkersLock() {
        String lockKey = "workflow:lock:user:919876543210";
        String tokenWorkerA = "worker-a-token-111";
        String tokenWorkerB = "worker-b-token-222";

        // 1. Worker A acquires lock
        when(valueOperations.setIfAbsent(eq(lockKey), eq(tokenWorkerA), any(Duration.class))).thenReturn(true);
        assertTrue(redisStateService.tryLock(lockKey, tokenWorkerA, Duration.ofMillis(100)));

        // 2. Lock expires in Redis, Worker B acquires with tokenWorkerB
        when(valueOperations.setIfAbsent(eq(lockKey), eq(tokenWorkerB), any(Duration.class))).thenReturn(true);
        assertTrue(redisStateService.tryLock(lockKey, tokenWorkerB, Duration.ofMinutes(1)));

        // 3. Worker A finishes late and attempts to unlock with tokenWorkerA
        // Lua script checks if redis.get(lockKey) == tokenWorkerA -> It returns 0 because current lock is tokenWorkerB
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(tokenWorkerA)))
                .thenReturn(0L);

        boolean workerAReleaseResult = redisStateService.unlock(lockKey, tokenWorkerA);
        assertFalse(workerAReleaseResult, "Worker A must NOT be able to delete Worker B's lock");

        // Verify stringRedisTemplate.delete(lockKey) was never blindly called
        verify(stringRedisTemplate, never()).delete(lockKey);

        // 4. Worker B unlocks with tokenWorkerB -> successfully deleted
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), eq(tokenWorkerB)))
                .thenReturn(1L);
        boolean workerBReleaseResult = redisStateService.unlock(lockKey, tokenWorkerB);
        assertTrue(workerBReleaseResult);
    }

    @Test
    @DisplayName("WorkflowStateTracker acquires user lock and releases safely with unique token")
    void testWorkflowStateTracker_UserLockFlow() {
        String waId = "919876543210";
        String lockKey = "workflow:lock:user:" + waId;

        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), any(Duration.class))).thenReturn(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), anyString()))
                .thenReturn(1L);

        boolean acquired = stateTracker.acquireUserLock(waId);
        assertTrue(acquired);

        stateTracker.releaseUserLock(waId);
        verify(stringRedisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList(lockKey)), anyString());
    }
}
