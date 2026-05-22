package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AIQuotaService {
    private static final Logger log = LoggerFactory.getLogger(AIQuotaService.class);

    @Autowired
    private TokenBudgetService tokenBudgetService;

    private static final long FREE_DAILY_LIMIT = 10_000;
    private static final long PRO_DAILY_LIMIT = 100_000;
    private static final long ENTERPRISE_DAILY_LIMIT = 1_000_000;

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }

    public void checkAndEnforceQuota(UUID tenantId, User.PlanType plan) {
        long limit = getDailyLimit(plan);
        long currentUsage = tokenBudgetService.getDailyUsage(tenantId);

        if (currentUsage >= limit) {
            log.error("[AI-Quota] HARD STOP: Tenant {} has exceeded their daily limit of {} tokens.", tenantId, limit);
            throw new QuotaExceededException("AI token daily limit exceeded. Please upgrade your plan or try again tomorrow.");
        }

        double usagePercentage = (double) currentUsage / limit;
        if (usagePercentage >= 0.95) {
            log.warn("[AI-Quota] CRITICAL: Tenant {} is at {}% of their daily token limit.", tenantId, String.format("%.1f", usagePercentage * 100));
        } else if (usagePercentage >= 0.90) {
            log.warn("[AI-Quota] WARNING: Tenant {} is at {}% of their daily token limit.", tenantId, String.format("%.1f", usagePercentage * 100));
        }
    }

    private long getDailyLimit(User.PlanType plan) {
        if (plan == null) return FREE_DAILY_LIMIT;
        return switch (plan) {
            case PRO -> PRO_DAILY_LIMIT;
            case ENTERPRISE -> ENTERPRISE_DAILY_LIMIT;
            default -> FREE_DAILY_LIMIT;
        };
    }
}
