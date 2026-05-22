package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStateService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

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
     * Simple distributed lock attempt.
     * @return true if lock acquired
     */
    public boolean tryLock(String lockKey, Duration ttl) {
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "LOCKED", ttl);
        return Boolean.TRUE.equals(acquired);
    }

    public void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }
}
