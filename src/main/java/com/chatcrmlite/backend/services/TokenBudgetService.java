package com.chatcrmlite.backend.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TokenBudgetService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String DAILY_KEY_PREFIX = "token_usage:daily:";
    private static final String MONTHLY_KEY_PREFIX = "token_usage:monthly:";

    /**
     * Records token usage for a tenant and returns the updated daily total.
     */
    public long recordTokenUsage(UUID tenantId, int inputTokens, int outputTokens) {
        int totalTokens = inputTokens + outputTokens;
        if (totalTokens <= 0) return 0;

        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String monthStr = YearMonth.now().toString();

        String dailyKey = DAILY_KEY_PREFIX + tenantId.toString() + ":" + dateStr;
        String monthlyKey = MONTHLY_KEY_PREFIX + tenantId.toString() + ":" + monthStr;

        // Atomic increment
        Long dailyTotal = redisTemplate.opsForValue().increment(dailyKey, totalTokens);
        redisTemplate.opsForValue().increment(monthlyKey, totalTokens);

        // Set expiry if it's the first increment (approx 48 hours for daily, 60 days for monthly)
        if (dailyTotal != null && dailyTotal == totalTokens) {
            redisTemplate.expire(dailyKey, 48, TimeUnit.HOURS);
            redisTemplate.expire(monthlyKey, 60, TimeUnit.DAYS);
        }

        return dailyTotal != null ? dailyTotal : 0;
    }

    /**
     * Gets the current daily token usage for a tenant.
     */
    public long getDailyUsage(UUID tenantId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String dailyKey = DAILY_KEY_PREFIX + tenantId.toString() + ":" + dateStr;
        
        String val = redisTemplate.opsForValue().get(dailyKey);
        return val != null ? Long.parseLong(val) : 0;
    }
}
