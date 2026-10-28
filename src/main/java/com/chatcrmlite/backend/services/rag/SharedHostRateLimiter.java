package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.exceptions.QuotaExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class SharedHostRateLimiter {

    public static final long SPACING_MS = 500;
    public static final long MAX_INLINE_WAIT_MS = 30_000; // 30 seconds
    public static final long BACKPRESSURE_THRESHOLD_MS = 15 * 60 * 1000; // 15 minutes

    private final JdbcTemplate jdbcTemplate;

    public record SlotReservation(
        String hostName,
        Instant nextPermittedAt,
        long waitMs,
        boolean shouldDefer,
        boolean backpressureExceeded
    ) {}

    /**
     * Atomically reserves a monotonically increasing dispatch slot for the specified host.
     * Guaranteed never to collapse distinct slots into the same timestamp.
     * Uses REQUIRES_NEW propagation so the lease reservation is committed immediately and visible to all concurrent workers.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SlotReservation reserveSlot(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            throw new IllegalArgumentException("Host name cannot be empty");
        }
        String host = rawHost.trim().toLowerCase(Locale.ROOT);

        String sql = """
            INSERT INTO rag_host_crawl_leases (host_name, next_permitted_at, updated_at)
            VALUES (?, CURRENT_TIMESTAMP + INTERVAL '500 milliseconds', CURRENT_TIMESTAMP)
            ON CONFLICT (host_name) DO UPDATE
            SET next_permitted_at = CASE 
                    WHEN rag_host_crawl_leases.next_permitted_at > CURRENT_TIMESTAMP 
                    THEN rag_host_crawl_leases.next_permitted_at + INTERVAL '500 milliseconds'
                    ELSE CURRENT_TIMESTAMP + INTERVAL '500 milliseconds'
                END,
                updated_at = CURRENT_TIMESTAMP
            RETURNING next_permitted_at;
            """;

        Timestamp ts = jdbcTemplate.queryForObject(sql, Timestamp.class, host);
        Instant nextPermittedAt = ts != null ? ts.toInstant() : Instant.now().plusMillis(SPACING_MS);
        Instant now = Instant.now();

        long waitMs = Math.max(0, Duration.between(now, nextPermittedAt).toMillis() - SPACING_MS);
        boolean shouldDefer = waitMs > MAX_INLINE_WAIT_MS;
        boolean backpressure = waitMs > BACKPRESSURE_THRESHOLD_MS;

        if (backpressure) {
            log.warn("[SharedHostRateLimiter] Backlog on host '{}' exceeds {} ms (wait: {} ms). Applying domain backpressure.",
                    host, BACKPRESSURE_THRESHOLD_MS, waitMs);
        } else if (shouldDefer) {
            log.info("[SharedHostRateLimiter] Slot for host '{}' requires wait of {} ms (> {} ms). Flagging for queue deferral.",
                    host, waitMs, MAX_INLINE_WAIT_MS);
        }

        return new SlotReservation(host, nextPermittedAt, waitMs, shouldDefer, backpressure);
    }

    /**
     * Acquires a slot and applies in-process politeness delay if wait is within inline threshold.
     * If backpressure threshold is exceeded, throws QuotaExceededException.
     */
    public void paceOutboundRequest(String host) throws InterruptedException {
        SlotReservation slot = reserveSlot(host);
        if (slot.backpressureExceeded()) {
            throw new QuotaExceededException("HOST_RATE_LIMIT", BACKPRESSURE_THRESHOLD_MS, slot.waitMs(), 0,
                    "Host rate limit backlog exceeded for domain '" + host + "'. Wait time exceeds 15 minutes.");
        }

        if (slot.waitMs() > 0) {
            log.debug("[SharedHostRateLimiter] Pacing request to '{}' for {} ms", host, slot.waitMs());
            Thread.sleep(slot.waitMs());
        }
    }
}
