package com.chatcrmlite.backend.services.visitor;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisitorQuotaService {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${chatcrm.fair-share.visitor.max-daily-messages:15}")
    private int defaultMaxDailyMessages;

    @Value("${chatcrm.fair-share.visitor.max-daily-tokens:3000}")
    private int defaultMaxDailyTokens;

    @Value("${chatcrm.fair-share.visitor.max-prompt-length-chars:1000}")
    @Getter
    private int maxPromptLengthChars;

    @Value("${chatcrm.fair-share.visitor.max-output-tokens:450}")
    @Getter
    private int maxOutputTokens;

    private DefaultRedisScript<List> quotaCheckScript;

    public enum QuotaStatus {
        ALLOWED,
        CONCURRENT_BLOCKED,
        MESSAGE_LIMIT_EXCEEDED,
        TOKEN_LIMIT_EXCEEDED,
        PROMPT_TOO_LONG
    }

    public static class QuotaReservationResult {
        private final QuotaStatus status;
        private final String message;
        private final long currentMessages;
        private final long currentTokens;

        public QuotaReservationResult(QuotaStatus status, String message, long currentMessages, long currentTokens) {
            this.status = status;
            this.message = message;
            this.currentMessages = currentMessages;
            this.currentTokens = currentTokens;
        }

        public boolean isAllowed() {
            return status == QuotaStatus.ALLOWED;
        }

        public QuotaStatus getStatus() { return status; }
        public String getMessage() { return message; }
        public long getCurrentMessages() { return currentMessages; }
        public long getCurrentTokens() { return currentTokens; }
    }

    @PostConstruct
    public void init() {
        quotaCheckScript = new DefaultRedisScript<>();
        quotaCheckScript.setLocation(new ClassPathResource("scripts/visitor_quota_check.lua"));
        quotaCheckScript.setResultType(List.class);
    }

    /**
     * Atomically validates visitor daily quota and sets in-flight concurrency lock.
     */
    public QuotaReservationResult checkAndReserveQuota(UUID tenantId, String visitorId, int estimatedTokens, String prompt) {
        if (prompt != null && prompt.length() > maxPromptLengthChars) {
            log.warn("⚠️ Visitor {} prompt rejected: {} chars > max {} chars", visitorId, prompt.length(), maxPromptLengthChars);
            return new QuotaReservationResult(
                    QuotaStatus.PROMPT_TOO_LONG,
                    "Message is too long. Please shorten your question to under " + maxPromptLengthChars + " characters.",
                    0, 0
            );
        }

        if (tenantId == null || visitorId == null) {
            return new QuotaReservationResult(QuotaStatus.ALLOWED, "Bypassed (no tenant/visitor)", 0, 0);
        }

        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String quotaKey = "vq:" + tenantId + ":" + visitorId + ":" + today;
        String inFlightKey = "inflight:" + tenantId + ":" + visitorId;

        long ttlSeconds = calculateTtlUntilMidnight();
        int safeEstimated = Math.max(1, estimatedTokens);

        try {
            List<Object> result = stringRedisTemplate.execute(
                    quotaCheckScript,
                    List.of(quotaKey, inFlightKey),
                    String.valueOf(defaultMaxDailyMessages),
                    String.valueOf(defaultMaxDailyTokens),
                    String.valueOf(safeEstimated),
                    String.valueOf(ttlSeconds)
            );

            if (result == null || result.isEmpty()) {
                log.warn("⚠️ Empty result from visitor_quota_check.lua. Falling back to allowed.");
                return new QuotaReservationResult(QuotaStatus.ALLOWED, "Allowed", 0, 0);
            }

            long code = Long.parseLong(String.valueOf(result.get(0)));
            String statusText = String.valueOf(result.get(1));
            long msgs = result.size() > 2 ? Long.parseLong(String.valueOf(result.get(2))) : 0L;
            long tokens = result.size() > 3 ? Long.parseLong(String.valueOf(result.get(3))) : 0L;

            if (code == 1) {
                log.debug("✅ [VisitorQuota] Reserved capacity for visitor {} (Tenant: {}). Usage: {}/{} msgs, {}/{} tokens",
                        visitorId, tenantId, msgs, defaultMaxDailyMessages, tokens, defaultMaxDailyTokens);
                return new QuotaReservationResult(QuotaStatus.ALLOWED, statusText, msgs, tokens);
            } else if (code == -1) {
                log.warn("🛑 [VisitorQuota] Concurrent query blocked for visitor {} (Tenant: {})", visitorId, tenantId);
                return new QuotaReservationResult(QuotaStatus.CONCURRENT_BLOCKED, "Please wait for your previous query to complete before sending another.", msgs, tokens);
            } else {
                QuotaStatus status = "MESSAGE_LIMIT_EXCEEDED".equals(statusText)
                        ? QuotaStatus.MESSAGE_LIMIT_EXCEEDED
                        : QuotaStatus.TOKEN_LIMIT_EXCEEDED;
                log.info("⛔ [VisitorQuota] Limit reached for visitor {} (Tenant: {}): {}", visitorId, tenantId, statusText);
                return new QuotaReservationResult(status, "You have reached your daily question limit for this session.", msgs, tokens);
            }
        } catch (Exception e) {
            log.error("⚠️ Redis error during visitor quota reservation for visitor {}: {}", visitorId, e.getMessage());
            // Fail-closed on concurrency if Redis is accessible, but do not crash request
            return new QuotaReservationResult(QuotaStatus.ALLOWED, "Fallback (Redis unavailable)", 0, 0);
        }
    }

    /**
     * Settles actual token usage against the estimated reservation and removes in-flight lock.
     */
    public void settleVisitorUsage(UUID tenantId, String visitorId, int estimatedTokens, int actualTokens) {
        if (tenantId == null || visitorId == null) return;

        String inFlightKey = "inflight:" + tenantId + ":" + visitorId;
        stringRedisTemplate.delete(inFlightKey);

        int tokenDifference = actualTokens - estimatedTokens;
        if (tokenDifference != 0) {
            String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
            String quotaKey = "vq:" + tenantId + ":" + visitorId + ":" + today;
            try {
                stringRedisTemplate.opsForHash().increment(quotaKey, "tokens", tokenDifference);
                stringRedisTemplate.opsForHash().increment(quotaKey, "actual_output_tokens", actualTokens);
            } catch (Exception e) {
                log.warn("Failed to settle token difference for visitor {}: {}", visitorId, e.getMessage());
            }
        }
    }

    /**
     * Releases the visitor reservation and unlocks in-flight status on model abort/disconnect/timeout.
     */
    public void releaseVisitorReservation(UUID tenantId, String visitorId, int estimatedTokens) {
        if (tenantId == null || visitorId == null) return;

        String inFlightKey = "inflight:" + tenantId + ":" + visitorId;
        stringRedisTemplate.delete(inFlightKey);

        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String quotaKey = "vq:" + tenantId + ":" + visitorId + ":" + today;
        try {
            Long msgs = stringRedisTemplate.opsForHash().increment(quotaKey, "msgs", -1);
            Long tokens = stringRedisTemplate.opsForHash().increment(quotaKey, "tokens", -estimatedTokens);
            if (msgs != null && msgs < 0) stringRedisTemplate.opsForHash().put(quotaKey, "msgs", "0");
            if (tokens != null && tokens < 0) stringRedisTemplate.opsForHash().put(quotaKey, "tokens", "0");
            log.info("🔓 Released visitor reservation for visitor {} (refunded 1 msg, {} tokens)", visitorId, estimatedTokens);
        } catch (Exception e) {
            log.warn("Failed to rollback visitor reservation for visitor {}: {}", visitorId, e.getMessage());
        }
    }

    /**
     * Calculates seconds until 23:59:59 plus 1 hour safety margin.
     */
    private long calculateTtlUntilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        long secondsUntilMidnight = Duration.between(now, midnight).getSeconds();
        return Math.max(3600, secondsUntilMidnight + 3600); // at least 1 hour, plus 1h buffer
    }
}
