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
        if (waMessageId == null || waMessageId.isBlank()) return true;

        String redisKey = REDIS_KEY_PREFIX + waMessageId;

        // 1. Redis Fast-Cache Guard (SetNX with 24h TTL)
        try {
            Boolean isNew = redisTemplate.opsForValue().setIfAbsent(redisKey, "CLAIMED", Duration.ofHours(24));
            if (Boolean.FALSE.equals(isNew)) {
                log.info("[Idempotency] Redis hit: Duplicate detected for {}", waMessageId);
                return false;
            }
        } catch (Exception e) {
            log.error("[Idempotency] Redis failure, falling back to database check", e);
            // Continue to DB check as fallback
        }

        // 2. Database UNIQUE Constraint Guard (The Source of Truth)
        try {
            User owner = userRepository.findById(tenantId).orElse(null);
            ProcessedMessage record = ProcessedMessage.builder()
                    .messageId(waMessageId)
                    .owner(owner)
                    .build();
            
            // saveAndFlush ensures the constraint check happens immediately within this transaction
            processedMessageRepository.saveAndFlush(record);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("[Idempotency] DB Race-condition: message {} already exists", waMessageId);
            return false;
        } catch (Exception e) {
            log.error("[Idempotency] Database failure during deduplication", e);
            // In case of unknown DB error, we return false to prevent side effects on unreliable state
            return false;
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
    @Transactional
    public void purgeOldRecords() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        int deleted = processedMessageRepository.deleteOlderThan(cutoff);
        log.info("[Idempotency] Purged {} old processed message records (cutoff={})", deleted, cutoff);
    }
}
