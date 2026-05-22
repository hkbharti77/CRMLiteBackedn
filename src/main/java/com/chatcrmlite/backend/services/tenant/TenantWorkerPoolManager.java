package com.chatcrmlite.backend.services.tenant;

import com.chatcrmlite.backend.models.User.PlanType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.Executor;

@lombok.extern.slf4j.Slf4j
@Service
public class TenantWorkerPoolManager {

    private final com.github.benmanes.caffeine.cache.Cache<String, ThreadPoolTaskExecutor> poolCache;
    private final TenantTierService tierService;

    public TenantWorkerPoolManager(TenantTierService tierService) {
        this.tierService = tierService;
        this.poolCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .expireAfterAccess(java.time.Duration.ofHours(2))
                .removalListener((key, value, cause) -> {
                    if (value instanceof ThreadPoolTaskExecutor executor) {
                        executor.shutdown();
                    }
                })
                .build();
    }

    public Executor getExecutor(UUID tenantId) {
        PlanType tier = tierService.getTier(tenantId);
        
        if (tier == PlanType.ENTERPRISE) {
            return poolCache.get("enterprise:" + tenantId, id -> createPool(20, 50, 500, "ent-" + tenantId + "-"));
        } else if (tier == PlanType.PRO) {
            return poolCache.get("pro-shared", id -> createPool(50, 100, 1000, "pro-shared-"));
        } else {
            return poolCache.get("free-shared", id -> createPool(10, 20, 200, "free-shared-"));
        }
    }

    private ThreadPoolTaskExecutor createPool(int core, int max, int queue, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        poolCache.asMap().values().forEach(ThreadPoolTaskExecutor::shutdown);
    }
}
