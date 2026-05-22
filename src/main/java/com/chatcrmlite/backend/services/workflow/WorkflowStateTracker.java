package com.chatcrmlite.backend.services.workflow;

import com.chatcrmlite.backend.services.RedisStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Tracks the state of messages in the distributed pipeline.
 * Ensures per-user sequential processing.
 */
@Service
@RequiredArgsConstructor
public class WorkflowStateTracker {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkflowStateTracker.class);

    private final RedisStateService redisStateService;

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
        String lockKey = "workflow:lock:user:" + waId;
        return redisStateService.tryLock(lockKey, Duration.ofMinutes(1));
    }

    public void releaseUserLock(String waId) {
        String lockKey = "workflow:lock:user:" + waId;
        redisStateService.unlock(lockKey);
    }
}
