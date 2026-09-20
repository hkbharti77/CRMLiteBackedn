package com.chatcrmlite.backend.services.whatsapp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundSendIdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(120);
    private static final long META_ACCEPTED_TTL_SECONDS = 86400; // 24 hours
    private static final long SUCCEEDED_TTL_SECONDS = 86400;     // 24 hours
    private static final long FAILED_TTL_SECONDS = 3600;         // 1 hour

    private static final String LUA_MARK_META_ACCEPTED = """
        local val = redis.call('GET', KEYS[1])
        if not val then return 0 end
        local obj = cjson.decode(val)
        if obj.executionId == ARGV[1] and (obj.status == 'IN_PROGRESS' or obj.status == 'FAILED') then
            obj.status = 'META_ACCEPTED'
            obj.waMessageId = ARGV[2]
            obj.metaAcceptedAt = tonumber(ARGV[3])
            redis.call('SET', KEYS[1], cjson.encode(obj), 'EX', tonumber(ARGV[4]))
            return 1
        end
        return 0
    """;

    private static final String LUA_MARK_SUCCEEDED = """
        local val = redis.call('GET', KEYS[1])
        if not val then return 0 end
        local obj = cjson.decode(val)
        if obj.status == 'META_ACCEPTED' or obj.status == 'IN_PROGRESS' then
            obj.status = 'SUCCEEDED'
            obj.crmMessageId = ARGV[1]
            obj.completedAt = tonumber(ARGV[2])
            redis.call('SET', KEYS[1], cjson.encode(obj), 'EX', tonumber(ARGV[3]))
            return 1
        end
        return 0
    """;

    private static final String LUA_MARK_FAILED = """
        local val = redis.call('GET', KEYS[1])
        if not val then return 0 end
        local obj = cjson.decode(val)
        if obj.executionId == ARGV[1] and obj.status == 'IN_PROGRESS' then
            obj.status = 'FAILED'
            obj.errorMessage = ARGV[2]
            redis.call('SET', KEYS[1], cjson.encode(obj), 'EX', tonumber(ARGV[3]))
            return 1
        end
        return 0
    """;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IdempotencyRecord {
        private String status;        // IN_PROGRESS, META_ACCEPTED, SUCCEEDED, FAILED
        private String executionId;
        private String payloadHash;
        private String waMessageId;
        private String crmMessageId;
        private String errorMessage;
        private Long startedAt;
        private Long metaAcceptedAt;
        private Long completedAt;
    }

    public enum ClaimStatus {
        ACQUIRED,
        IN_PROGRESS,
        META_ACCEPTED,
        SUCCEEDED,
        CONFLICT
    }

    public record ClaimResult(
        ClaimStatus status,
        String executionId,
        String waMessageId,
        String crmMessageId,
        String conflictMessage
    ) {
        public static ClaimResult acquired(String executionId) {
            return new ClaimResult(ClaimStatus.ACQUIRED, executionId, null, null, null);
        }

        public static ClaimResult inProgress() {
            return new ClaimResult(ClaimStatus.IN_PROGRESS, null, null, null, null);
        }

        public static ClaimResult metaAccepted(String waMessageId, String crmMessageId) {
            return new ClaimResult(ClaimStatus.META_ACCEPTED, null, waMessageId, crmMessageId, null);
        }

        public static ClaimResult succeeded(String waMessageId, String crmMessageId) {
            return new ClaimResult(ClaimStatus.SUCCEEDED, null, waMessageId, crmMessageId, null);
        }

        public static ClaimResult conflict(String message) {
            return new ClaimResult(ClaimStatus.CONFLICT, null, null, null, message);
        }
    }

    public String buildKey(UUID tenantId, String requestId) {
        return "outbound:send:" + tenantId + ":" + requestId;
    }

    public String computePayloadHash(String rawContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawContent.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public ClaimResult claimOrCheck(String key, String payloadHash, String executionId) {
        try {
            IdempotencyRecord initialRecord = IdempotencyRecord.builder()
                    .status("IN_PROGRESS")
                    .executionId(executionId)
                    .payloadHash(payloadHash)
                    .startedAt(System.currentTimeMillis())
                    .build();

            String initialJson = objectMapper.writeValueAsString(initialRecord);
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, initialJson, IN_PROGRESS_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("[Idempotency] Acquired send claim key={} executionId={}", key, executionId);
                return ClaimResult.acquired(executionId);
            }

            // Key already exists, read current record
            String existingJson = redisTemplate.opsForValue().get(key);
            if (existingJson == null) {
                // Expired between setIfAbsent and get, retry atomic claim
                Boolean retrySet = redisTemplate.opsForValue().setIfAbsent(key, initialJson, IN_PROGRESS_TTL);
                if (Boolean.TRUE.equals(retrySet)) {
                    return ClaimResult.acquired(executionId);
                }
                existingJson = redisTemplate.opsForValue().get(key);
            }

            if (existingJson == null) {
                return ClaimResult.inProgress();
            }

            IdempotencyRecord existing = objectMapper.readValue(existingJson, IdempotencyRecord.class);

            // Check for payload conflict
            if (existing.getPayloadHash() != null && !existing.getPayloadHash().equals(payloadHash)) {
                log.warn("[Idempotency] Payload mismatch conflict key={} storedHash={} incomingHash={}",
                        key, existing.getPayloadHash(), payloadHash);
                return ClaimResult.conflict("Request ID reused with different payload parameters.");
            }

            String status = existing.getStatus();
            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                log.info("[Idempotency] Key {} already SUCCEEDED with waMessageId={}", key, existing.getWaMessageId());
                return ClaimResult.succeeded(existing.getWaMessageId(), existing.getCrmMessageId());
            }

            if ("META_ACCEPTED".equalsIgnoreCase(status)) {
                log.warn("[Idempotency] Key {} already META_ACCEPTED with waMessageId={}. Recovery required.",
                        key, existing.getWaMessageId());
                return ClaimResult.metaAccepted(existing.getWaMessageId(), existing.getCrmMessageId());
            }

            if ("FAILED".equalsIgnoreCase(status)) {
                log.info("[Idempotency] Key {} previously FAILED, reclaiming with executionId={}", key, executionId);
                redisTemplate.opsForValue().set(key, initialJson, IN_PROGRESS_TTL);
                return ClaimResult.acquired(executionId);
            }

            log.info("[Idempotency] Key {} is currently IN_PROGRESS", key);
            return ClaimResult.inProgress();

        } catch (Exception ex) {
            log.error("[Idempotency] Error checking idempotency key={}: {}", key, ex.getMessage(), ex);
            throw new RuntimeException("Idempotency service error", ex);
        }
    }

    public boolean markMetaAccepted(String key, String executionId, String waMessageId) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_MARK_META_ACCEPTED, Long.class);
            Long result = redisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    executionId,
                    waMessageId,
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(META_ACCEPTED_TTL_SECONDS)
            );
            boolean updated = result != null && result == 1L;
            if (updated) {
                log.info("[Idempotency] Marked META_ACCEPTED key={} executionId={} waMessageId={}",
                        key, executionId, waMessageId);
            } else {
                log.warn("[Idempotency] Failed to mark META_ACCEPTED for key={} executionId={} (superseded or expired)",
                        key, executionId);
            }
            return updated;
        } catch (Exception ex) {
            log.error("[Idempotency] Error marking META_ACCEPTED for key={}: {}", key, ex.getMessage(), ex);
            return false;
        }
    }

    public boolean markSucceeded(String key, String crmMessageId) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_MARK_SUCCEEDED, Long.class);
            Long result = redisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    crmMessageId != null ? crmMessageId : "",
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(SUCCEEDED_TTL_SECONDS)
            );
            boolean updated = result != null && result == 1L;
            if (updated) {
                log.info("[Idempotency] Marked SUCCEEDED key={} crmMessageId={}", key, crmMessageId);
            } else {
                log.warn("[Idempotency] Failed to mark SUCCEEDED for key={}", key);
            }
            return updated;
        } catch (Exception ex) {
            log.error("[Idempotency] Error marking SUCCEEDED for key={}: {}", key, ex.getMessage(), ex);
            return false;
        }
    }

    public boolean markFailed(String key, String executionId, String errorMessage) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_MARK_FAILED, Long.class);
            Long result = redisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    executionId,
                    errorMessage != null ? errorMessage : "Unknown error",
                    String.valueOf(FAILED_TTL_SECONDS)
            );
            return result != null && result == 1L;
        } catch (Exception ex) {
            log.error("[Idempotency] Error marking FAILED for key={}: {}", key, ex.getMessage(), ex);
            return false;
        }
    }
}
