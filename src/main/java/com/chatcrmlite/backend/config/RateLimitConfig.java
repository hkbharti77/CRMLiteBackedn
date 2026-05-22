package com.chatcrmlite.backend.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting using Bucket4j with a Redis-backed fallback strategy.
 *
 * In production (multi-pod), this should be backed by Redis (Bucket4j-Redis).
 * The current implementation uses a local in-memory map which is fast but
 * not shared across pod instances. For full distributed rate limiting, use:
 *
 *   io.github.bucket4j:bucket4j-redis:8.x
 *
 * Different limit tiers are supported:
 *   - WEBHOOK:  30 req/min  (WhatsApp webhook bursts)
 *   - API:      60 req/min  (authenticated API callers)
 *   - PUBLIC:    5 req/min  (unauthenticated public endpoints)
 *   - AI:       10 req/min  (AI/RAG endpoint — expensive upstream)
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RateLimitConfig {

    public enum Tier {
        WEBHOOK(30),
        API(60),
        PUBLIC(5),
        AI(10);

        final int requestsPerMinute;
        Tier(int rpm) { this.requestsPerMinute = rpm; }
    }

    private final StringRedisTemplate redisTemplate;

    // Local fallback cache — acceptable for single-instance or low-scale deployments.
    // For K8s multi-replica, migrate to Bucket4j-Redis or Nginx rate limiting.
    private final Map<String, Bucket> bucketCache = new ConcurrentHashMap<>();

    /**
     * Try to consume 1 token from the bucket for the given key+tier.
     *
     * @param key  Unique identifier (IP, userId, or tenantId)
     * @param tier Which rate limit tier to apply
     * @return true if request is allowed, false if rate limited
     */
    public boolean tryConsume(String key, Tier tier) {
        String bucketKey = tier.name() + ":" + key;
        Bucket bucket = bucketCache.computeIfAbsent(bucketKey, k -> createBucket(tier));
        boolean allowed = bucket.tryConsume(1);
        if (!allowed) {
            log.warn("[RateLimit] BLOCKED key={} tier={}", key, tier.name());
        }
        return allowed;
    }

    public Bucket resolveBucket(String ipAddress) {
        return bucketCache.computeIfAbsent(ipAddress, k -> createBucket(Tier.PUBLIC));
    }

    private Bucket createBucket(Tier tier) {
        Bandwidth limit = Bandwidth.classic(
            tier.requestsPerMinute,
            Refill.intervally(tier.requestsPerMinute, Duration.ofMinutes(1))
        );
        return Bucket.builder().addLimit(limit).build();
    }

    public void clearCache() {
        bucketCache.clear();
        log.info("[RateLimit] Cache cleared");
    }
}
