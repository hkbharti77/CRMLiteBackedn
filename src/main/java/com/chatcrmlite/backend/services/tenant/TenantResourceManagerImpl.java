package com.chatcrmlite.backend.services.tenant;

import com.chatcrmlite.backend.models.User.PlanType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantResourceManagerImpl implements TenantResourceManager {

    private final StringRedisTemplate redisTemplate;
    private final com.chatcrmlite.backend.services.tenant.TenantTierService tierService;
    
    // Key Patterns
    private static final String QUOTA_USAGE_KEY = "quota:usage:%s:%s"; // quota:usage:tenantId:resourceType
    private static final String RATE_LIMIT_KEY = "ratelimit:%s:%s"; // ratelimit:tenantId:resourceType

    @Override
    public boolean canConsume(UUID tenantId, ResourceType type, int amount) {
        long limit = getLimitForTier(tenantId, type);
        
        if (type == ResourceType.MESSAGES_PER_SECOND) {
            return checkRateLimit(tenantId, type, limit);
        }

        String key = String.format(QUOTA_USAGE_KEY, tenantId, type);
        String currentVal = redisTemplate.opsForValue().get(key);
        long current = currentVal != null ? Long.parseLong(currentVal) : 0;

        return (current + amount) <= limit;
    }

    @Override
    public void reportUsage(UUID tenantId, ResourceType type, int amount) {
        String key = String.format(QUOTA_USAGE_KEY, tenantId, type);
        redisTemplate.opsForValue().increment(key, amount);
        
        // Reset usage daily for certain resources like AI tokens
        if (type == ResourceType.AI_TOKENS) {
            redisTemplate.expire(key, 24, TimeUnit.HOURS);
        }
    }

    @Override
    public QuotaStatus getStatus(UUID tenantId, ResourceType type) {
        String key = String.format(QUOTA_USAGE_KEY, tenantId, type);
        String currentVal = redisTemplate.opsForValue().get(key);
        long current = currentVal != null ? Long.parseLong(currentVal) : 0;
        return new QuotaStatus(current, getLimitForTier(tenantId, type));
    }

    private boolean checkRateLimit(UUID tenantId, ResourceType type, long limit) {
        String key = String.format(RATE_LIMIT_KEY, tenantId, type);
        long now = System.currentTimeMillis();
        
        // Simplified sliding window using Redis sorted sets
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, now - 1000);
        Long count = redisTemplate.opsForZSet().zCard(key);
        
        if (count != null && count < limit) {
            redisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(), now);
            redisTemplate.expire(key, 2, TimeUnit.SECONDS);
            return true;
        }
        return false;
    }

    private long getLimitForTier(UUID tenantId, ResourceType type) {
        if (type == null) return 1000;
        var tier = tierService.getTier(tenantId);
        
        if (type == ResourceType.MESSAGES_PER_SECOND) {
            if (tier == PlanType.FREE) return 2;
            if (tier == PlanType.ENTERPRISE) return 50;
            return 10; // PRO / Default
        }
        
        if (type == ResourceType.AI_TOKENS) {
            if (tier == PlanType.FREE) return 50000;
            if (tier == PlanType.ENTERPRISE) return 1000000;
            return 200000; // PRO / Default
        }
        
        if (type == ResourceType.MAX_CONNECTIONS) return 50;
        if (type == ResourceType.CPU_THREADS) return 10;
        if (type == ResourceType.QUEUE_DEPTH) return 1000;
        return 1000;
    }
}
