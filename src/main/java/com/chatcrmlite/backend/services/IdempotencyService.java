package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.ProcessedMessage;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ProcessedMessageRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Distributed Idempotency Guard Service using INSERT-FIRST strategy.
 *
 * Problem: WhatsApp webhooks can be retried or delivered multiple times.
 * This service ensures each message is processed exactly once by using
 * both a Redis fast-cache and a database unique constraint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final ProcessedMessageRepository processedMessageRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    @org.springframework.beans.factory.annotation.Value("${app.performance.threshold.cache-ms:10}")
    private long cacheThresholdMs;

    private static final String REDIS_KEY_PREFIX = "webhook_idempotency:";

    /**
     * Checks if this WhatsApp message ID has already been handled.
     * @deprecated Use {@link #markAsProcessing(String, UUID)} for atomic check-and-set.
     */
    @Deprecated
    public boolean isAlreadyProcessed(String messageId) {
        if (messageId == null || messageId.isBlank()) return false;
        return processedMessageRepository.existsByMessageId(messageId);
    }

    /**
     * Attempts to claim a message ID for processing using an atomic INSERT-FIRST strategy.
     *
     * @param waMessageId WhatsApp message ID (wamid)
     * @param tenantId    The UUID of the tenant (owner)
     * @return true if this call successfully claimed the message (first to process)
     *         false if the message is a duplicate (already processing or processed)
     */
    public boolean markAsProcessing(String waMessageId, UUID tenantId) {
        long start = System.currentTimeMillis();
        if (waMessageId == null || waMessageId.isBlank()) return true;

        String redisKey = REDIS_KEY_PREFIX + waMessageId;

        // 1. Redis Fast-Cache Guard (SetNX with 24h TTL)
        try {
            long redisStart = System.currentTimeMillis();
            Boolean isNew = redisTemplate.opsForValue().setIfAbsent(redisKey, "CLAIMED", Duration.ofHours(24));
            long redisDuration = System.currentTimeMillis() - redisStart;
            if (redisDuration > cacheThresholdMs) {
                log.warn("SLOW_OPERATION: Redis idempotency check exceeded threshold traceId={} durationMs={} thresholdMs={}", 
                         org.slf4j.MDC.get("traceId"), redisDuration, cacheThresholdMs);
            }

            if (Boolean.FALSE.equals(isNew)) {
                log.info("Duplicate message detected in Redis tenantId={} traceId={} messageId={} processingTimeMs={}", 
                         tenantId, org.slf4j.MDC.get("traceId"), waMessageId, System.currentTimeMillis() - start);
                return false;
            }
        } catch (org.springframework.data.redis.RedisConnectionFailureException | org.springframework.data.redis.RedisSystemException e) {
            log.error("Redis infrastructure failure tenantId={} traceId={} messageId={} errorType={} processingTimeMs={}", 
                      tenantId, org.slf4j.MDC.get("traceId"), waMessageId, e.getClass().getSimpleName(), System.currentTimeMillis() - start, e);
            throw new com.chatcrmlite.backend.exceptions.InfrastructureException("Redis failure during deduplication", e);
        } catch (Exception e) {
            log.warn("Unexpected Redis exception continuing to DB tenantId={} traceId={} messageId={} errorType={}", 
                     tenantId, org.slf4j.MDC.get("traceId"), waMessageId, e.getClass().getSimpleName(), e);
        }

        // 2. Database UNIQUE Constraint Guard (The Source of Truth)
        try {
            transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transactionTemplate.executeWithoutResult(status -> {
                UUID userId = userRepository.findFirstUserIdByTenantId(tenantId)
                        .orElseThrow(() -> new IllegalArgumentException("Tenant not found or has no users: " + tenantId));
                User owner = userRepository.getReferenceById(userId);
                
                ProcessedMessage record = ProcessedMessage.builder()
                        .messageId(waMessageId)
                        .owner(owner)
                        .build();
                
                // saveAndFlush ensures any constraint violations are thrown immediately
                processedMessageRepository.saveAndFlush(record);
            });
            return true;
        } catch (DataIntegrityViolationException e) {
            // Because we resolve the UUID first, the only expected integrity violation here is the UNIQUE constraint on messageId.
            log.warn("Database duplicate race-condition messageId={} tenantId={} traceId={} processingTimeMs={}", 
                     waMessageId, tenantId, org.slf4j.MDC.get("traceId"), System.currentTimeMillis() - start);
            return false;
        } catch (IllegalArgumentException e) {
            log.error("Validation failure tenantId={} traceId={} errorType={} processingTimeMs={}", 
                      tenantId, org.slf4j.MDC.get("traceId"), e.getClass().getSimpleName(), System.currentTimeMillis() - start, e);
            throw e;
        } catch (org.springframework.dao.DataAccessException e) {
            log.error("Database infrastructure failure tenantId={} traceId={} messageId={} errorType={} processingTimeMs={}", 
                      tenantId, org.slf4j.MDC.get("traceId"), waMessageId, e.getClass().getSimpleName(), System.currentTimeMillis() - start, e);
            throw new com.chatcrmlite.backend.exceptions.InfrastructureException("Database failure during deduplication", e);
        }
    }

    private static final String REDIS_LOCK_PREFIX = "webhook:lock:";
    private static final String REDIS_PROCESSED_PREFIX = "webhook:processed:";

    /**
     * Checks if a WAMID has already been successfully committed to the database.
     */
    public boolean isProcessed(String waMessageId, UUID tenantId) {
        if (waMessageId == null || waMessageId.isBlank()) return false;
        String processedKey = REDIS_PROCESSED_PREFIX + tenantId + ":" + waMessageId;
        Boolean exists = redisTemplate.hasKey(processedKey);
        if (Boolean.TRUE.equals(exists)) {
            return true;
        }
        boolean inDb = processedMessageRepository.existsByMessageId(waMessageId);
        if (inDb) {
            redisTemplate.opsForValue().set(processedKey, "PROCESSED", Duration.ofHours(72));
            return true;
        }
        return false;
    }

    /**
     * Acquires a short-lived processing lock (2 minutes TTL) using atomic SetNX.
     * Prevents concurrent worker races without causing permanent message loss if a worker crashes.
     */
    public boolean acquireProcessingLock(String waMessageId, UUID tenantId, String workerId) {
        if (waMessageId == null || waMessageId.isBlank()) return true;
        String lockKey = REDIS_LOCK_PREFIX + tenantId + ":" + waMessageId;
        try {
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, workerId != null ? workerId : "LOCKED", Duration.ofMinutes(2));
            return Boolean.TRUE.equals(locked);
        } catch (Exception e) {
            log.warn("Redis error while acquiring processing lock for messageId={}: {}", waMessageId, e.getMessage());
            return true; // fail-open to allow DB unique constraint to govern
        }
    }

    /**
     * Marks the message as successfully processed:
     * 1. Inserts into ProcessedMessage DB table (idempotent unique constraint authority)
     * 2. Sets long-lived Redis key (72 hours TTL)
     * 3. Releases the short-lived processing lock
     */
    public void markAsProcessedSuccess(String waMessageId, UUID tenantId) {
        if (waMessageId == null || waMessageId.isBlank()) return;
        String lockKey = REDIS_LOCK_PREFIX + tenantId + ":" + waMessageId;
        String processedKey = REDIS_PROCESSED_PREFIX + tenantId + ":" + waMessageId;

        try {
            // DB persistence
            transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transactionTemplate.executeWithoutResult(status -> {
                UUID userId = userRepository.findFirstUserIdByTenantId(tenantId)
                        .orElse(null);
                if (userId != null) {
                    User owner = userRepository.getReferenceById(userId);
                    ProcessedMessage record = ProcessedMessage.builder()
                            .messageId(waMessageId)
                            .owner(owner)
                            .build();
                    processedMessageRepository.saveAndFlush(record);
                }
            });
        } catch (DataIntegrityViolationException e) {
            // Expected on duplicate race
            log.debug("ProcessedMessage DB entry already exists for messageId={}", waMessageId);
        } catch (Exception e) {
            log.warn("Error recording ProcessedMessage DB row for messageId={}: {}", waMessageId, e.getMessage());
        }

        try {
            redisTemplate.opsForValue().set(processedKey, "PROCESSED", Duration.ofHours(72));
            redisTemplate.delete(lockKey);
        } catch (Exception e) {
            log.warn("Redis error updating processed status for messageId={}: {}", waMessageId, e.getMessage());
        }
    }

    /**
     * Releases short-lived lock if processing failed before commit.
     */
    public void releaseProcessingLock(String waMessageId, UUID tenantId) {
        if (waMessageId == null || waMessageId.isBlank()) return;
        String lockKey = REDIS_LOCK_PREFIX + tenantId + ":" + waMessageId;
        try {
            redisTemplate.delete(lockKey);
        } catch (Exception e) {
            log.warn("Redis error releasing lock for messageId={}: {}", waMessageId, e.getMessage());
        }
    }

    /**
     * Legacy method for backward compatibility.
     */
    @Transactional
    public boolean markAsProcessed(String messageId, User owner) {
        return markAsProcessing(messageId, owner != null ? owner.getId() : null);
    }

    @Scheduled(fixedDelay = 86_400_000) // every 24 hours
    @SchedulerLock(name = "IdempotencyService_purgeOldRecords", lockAtMostFor = "1h", lockAtLeastFor = "30m")
    public void scheduledPurgeOldRecords() {
        purgeOldRecordsInternal();
    }

    public void purgeOldRecordsInternal() {
        long start = System.currentTimeMillis();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        int totalDeleted = 0;
        int batchSize = 5000;
        
        log.info("Starting batch purge traceId={} cutoff={}", org.slf4j.MDC.get("traceId"), cutoff);
        while (true) {
            java.util.List<Long> idsToDelete = processedMessageRepository.findIdsOlderThan(
                    cutoff, 
                    org.springframework.data.domain.PageRequest.of(0, batchSize)
            );
            
            if (idsToDelete.isEmpty()) {
                break;
            }
            
            int deleted = processedMessageRepository.deleteByIdIn(idsToDelete);
            totalDeleted += deleted;
            log.info("Purged batch traceId={} deleted={} totalDeleted={}", org.slf4j.MDC.get("traceId"), deleted, totalDeleted);
            
            if (idsToDelete.size() < batchSize) {
                break;
            }
        }
        log.info("Finished batch purge traceId={} totalDeleted={} cutoff={} processingTimeMs={}", 
                 org.slf4j.MDC.get("traceId"), totalDeleted, cutoff, System.currentTimeMillis() - start);
    }
}
