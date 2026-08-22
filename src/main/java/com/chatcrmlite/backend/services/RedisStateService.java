package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStateService {

    private static final String UNLOCK_LUA_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> unlockScript = new DefaultRedisScript<>(UNLOCK_LUA_SCRIPT, Long.class);

    /**
     * Build a tenant-aware namespaced key.
     * Format: tenant:{tenantId}:{domain}:{subKey}
     */
    public String buildKey(UUID tenantId, String domain, String subKey) {
        return String.format("tenant:%s:%s:%s", 
            tenantId != null ? tenantId.toString() : "global", 
            domain, 
            subKey);
    }

    public void set(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    public <T> T get(String key, Class<T> clazz) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) return null;
        return clazz.cast(value);
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }

    public Long increment(String key, Duration ttl) {
        Long val = stringRedisTemplate.opsForValue().increment(key);
        if (val != null && val == 1) {
            stringRedisTemplate.expire(key, ttl);
        }
        return val;
    }

    /**
     * Atomic distributed lock with a unique owner token.
     */
    public boolean tryLock(String lockKey, String ownerToken, Duration ttl) {
        if (ownerToken == null || ownerToken.isBlank()) {
            ownerToken = "LOCKED";
        }
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, ownerToken, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Backward-compatible simple distributed lock attempt.
     */
    public boolean tryLock(String lockKey, Duration ttl) {
        return tryLock(lockKey, "LOCKED", ttl);
    }

    /**
     * Atomically releases the distributed lock ONLY if the stored value matches ownerToken.
     */
    public boolean unlock(String lockKey, String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            stringRedisTemplate.delete(lockKey);
            return true;
        }
        Long result = stringRedisTemplate.execute(
                unlockScript,
                Collections.singletonList(lockKey),
                ownerToken
        );
        return result != null && result > 0;
    }

    /**
     * Backward-compatible simple unlock.
     */
    public void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }
}
