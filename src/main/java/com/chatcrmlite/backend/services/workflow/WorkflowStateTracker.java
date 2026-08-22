package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.services.RedisStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the state of messages in the distributed pipeline.
 * Ensures per-user sequential processing with owner-safe distributed locking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowStateTracker {

    private final RedisStateService redisStateService;
    private final Map<String, String> userLockTokens = new ConcurrentHashMap<>();

    public void trackStage(String messageId, ProcessingContext.WorkflowStage stage) {
        String key = "workflow:state:" + messageId;
        redisStateService.set(key, stage.name(), Duration.ofHours(24));
        log.debug("[Workflow] Message {} transitioned to {}", messageId, stage);
    }

    /**
     * Try to acquire an ordering lock for a specific user.
     * Prevents Message B from overtaking Message A if both are in flight.
     */
    public boolean acquireUserLock(String waId) {
        return acquireUserLock(waId, Duration.ofSeconds(3), Duration.ofMinutes(1));
    }

    public boolean acquireUserLock(String waId, Duration waitTimeout, Duration lockTtl) {
        if (waId == null || waId.isBlank()) return true;
        String lockKey = "workflow:lock:user:" + waId;
        long deadline = System.currentTimeMillis() + waitTimeout.toMillis();
        String ownerToken = UUID.randomUUID().toString();

        do {
            if (redisStateService.tryLock(lockKey, ownerToken, lockTtl)) {
                userLockTokens.put(waId, ownerToken);
                log.debug("[Workflow] Acquired user lock for waId={} with token={}", waId, ownerToken);
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Workflow] Interrupted while waiting for user lock for waId={}", waId);
                return false;
            }
        } while (System.currentTimeMillis() < deadline);

        log.warn("⚠️ [Workflow] Timed out waiting for user lock for waId={}", waId);
        return false;
    }

    public void releaseUserLock(String waId) {
        if (waId == null || waId.isBlank()) return;
        String lockKey = "workflow:lock:user:" + waId;
        String ownerToken = userLockTokens.remove(waId);
        redisStateService.unlock(lockKey, ownerToken);
        log.debug("[Workflow] Released user lock for waId={} with token={}", waId, ownerToken);
    }
}
