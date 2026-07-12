package com.chatcrmlite.backend.services.tenant;

import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.models.User.PlanType;
import com.chatcrmlite.backend.repositories.TenantSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantTierServiceTest {

    @Mock
    private TenantSubscriptionRepository tenantSubscriptionRepository;

    private io.micrometer.core.instrument.MeterRegistry meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private TenantTierService tenantTierService;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenantTierService = new TenantTierService(tenantSubscriptionRepository, meterRegistry);
    }

    @Test
    void testCacheMissAndHit() {
        TenantSubscription mockSub = mock(TenantSubscription.class, RETURNS_DEEP_STUBS);
        when(mockSub.getPlan().getId()).thenReturn("PRO");

        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(mockSub));

        // First call: Cache Miss
        PlanType tier1 = tenantTierService.getTier(tenantId);
        assertEquals(PlanType.PRO, tier1);
        verify(tenantSubscriptionRepository, times(1)).findByTenantId(tenantId);

        // Second call: Cache Hit
        PlanType tier2 = tenantTierService.getTier(tenantId);
        assertEquals(PlanType.PRO, tier2);
        
        // Repository should not be called again
        verify(tenantSubscriptionRepository, times(1)).findByTenantId(tenantId);
    }

    @Test
    void testCacheInvalidationAfterPlanUpgrade() {
        // Initial plan FREE
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        PlanType tier1 = tenantTierService.getTier(tenantId);
        assertEquals(PlanType.FREE, tier1);
        verify(tenantSubscriptionRepository, times(1)).findByTenantId(tenantId);

        // Upgrade plan
        TenantSubscription mockSub = mock(TenantSubscription.class, RETURNS_DEEP_STUBS);
        when(mockSub.getPlan().getId()).thenReturn("ENTERPRISE");
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(mockSub));

        // Invalidate cache
        tenantTierService.invalidateCache(tenantId);

        // Should fetch new plan from DB
        PlanType tier2 = tenantTierService.getTier(tenantId);
        assertEquals(PlanType.ENTERPRISE, tier2);
        verify(tenantSubscriptionRepository, times(2)).findByTenantId(tenantId);
    }

    @Test
    void testCacheExpiration() throws Exception {
        // Use reflection to inject a short-lived cache for testing expiration
        Field cacheField = TenantTierService.class.getDeclaredField("tierCache");
        cacheField.setAccessible(true);
        com.github.benmanes.caffeine.cache.Cache<UUID, PlanType> shortLivedCache = 
                com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                        .expireAfterWrite(java.time.Duration.ofMillis(100))
                        .build();
        cacheField.set(tenantTierService, shortLivedCache);

        TenantSubscription mockSub = mock(TenantSubscription.class, RETURNS_DEEP_STUBS);
        when(mockSub.getPlan().getId()).thenReturn("PRO");

        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(mockSub));

        // First call: Cache Miss
        PlanType tier1 = tenantTierService.getTier(tenantId);
        assertEquals(PlanType.PRO, tier1);
        
        // Wait for cache to expire
        Thread.sleep(150);
        
        // Second call: Should be a Miss again due to expiration
        PlanType tier2 = tenantTierService.getTier(tenantId);
        assertEquals(PlanType.PRO, tier2);
        
        // Verify repository was called twice
        verify(tenantSubscriptionRepository, times(2)).findByTenantId(tenantId);
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        TenantSubscription mockSub = mock(TenantSubscription.class, RETURNS_DEEP_STUBS);
        when(mockSub.getPlan().getId()).thenReturn("PRO");

        // Add a slight delay to simulate DB call and increase chance of race condition
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenAnswer(invocation -> {
            Thread.sleep(10);
            return Optional.of(mockSub);
        });

        int numberOfThreads = 10;
        ExecutorService service = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        for (int i = 0; i < numberOfThreads; i++) {
            service.execute(() -> {
                tenantTierService.getTier(tenantId);
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        
        // Thanks to Caffeine's synchronous computing cache, it should compute only once even under high concurrency
        verify(tenantSubscriptionRepository, times(1)).findByTenantId(tenantId);
    }
}
