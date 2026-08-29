package com.chatcrmlite.backend.services.tenant;

import com.chatcrmlite.backend.controllers.dev.DevMockPaymentController;
import com.chatcrmlite.backend.models.BillingTransaction;
import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BillingTransactionRepository;
import com.chatcrmlite.backend.repositories.SubscriptionPlanRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.TenantSubscriptionRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
public class SubscriptionCacheInvalidationIntegrationTest {

    @Autowired
    private TenantTierService tenantTierService;

    @Autowired
    private QuotaEnforcerService quotaEnforcerService;

    @Autowired
    private DevMockPaymentController devMockPaymentController;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantSubscriptionRepository tenantSubscriptionRepository;

    @Autowired
    private SubscriptionPlanRepository subscriptionPlanRepository;

    @Autowired
    private BillingTransactionRepository billingTransactionRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Tenant testTenant;
    private User testUser;

    @BeforeEach
    void setUp() {
        // Ensure plans exist
        if (!subscriptionPlanRepository.existsById("FREE")) {
            SubscriptionPlan free = new SubscriptionPlan();
            free.setId("FREE");
            free.setName("Free Plan");
            free.setPriceMonthly(BigDecimal.ZERO);
            free.setPriceYearly(BigDecimal.ZERO);
            subscriptionPlanRepository.save(free);
        }
        if (!subscriptionPlanRepository.existsById("PRO")) {
            SubscriptionPlan pro = new SubscriptionPlan();
            pro.setId("PRO");
            pro.setName("Pro Plan");
            pro.setPriceMonthly(BigDecimal.valueOf(99));
            pro.setPriceYearly(BigDecimal.valueOf(990));
            subscriptionPlanRepository.save(pro);
        }

        transactionTemplate.executeWithoutResult(status -> {
            // Setup tenant and user
            testTenant = new Tenant();
            testTenant.setBusinessName("Cache Invalidation Test Business");
            testTenant = tenantRepository.save(testTenant);

            testUser = new User();
            testUser.setEmail("cachetest-" + UUID.randomUUID() + "@example.com");
            testUser.setPassword("password");
            testUser.setTenant(testTenant);
            testUser = userRepository.save(testUser);
        });

        // Clear cache
        tenantTierService.invalidateCache(testTenant.getId());
    }

    @AfterEach
    void tearDown() {
        tenantSubscriptionRepository.deleteAll();
        billingTransactionRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    @Test
    void testFreeToProUpdate_InvalidatesCache() {
        // 1. Initial lookup -> caches FREE (since no sub exists, QuotaEnforcer will create one)
        assertEquals(User.PlanType.FREE, tenantTierService.getTier(testTenant.getId()));

        // 2. Execute Payment Webhook update inside a separate transaction to trigger AFTER_COMMIT event
        transactionTemplate.executeWithoutResult(status -> {
            BillingTransaction tx = BillingTransaction.builder()
                    .amount(BigDecimal.valueOf(100))
                    .currency("USD")
                    .status(BillingTransaction.TransactionStatus.PENDING)
                    .paymentGateway(BillingTransaction.PaymentGateway.STRIPE)
                    .gatewayTransactionId("test_order_123")
                    .tenant(testTenant)
                    .build();
            billingTransactionRepository.save(tx);

            Map<String, String> request = new HashMap<>();
            request.put("orderId", "test_order_123");
            
            // This should update to PRO and fire the event
            devMockPaymentController.mockPaymentSuccess(testUser.getEmail(), request);
        });

        // 3. Second lookup -> cache should have been invalidated, pulling PRO from DB
        assertEquals(User.PlanType.PRO, tenantTierService.getTier(testTenant.getId()));
    }

    @Test
    void testProToFreeDowngrade_InvalidatesCache() {
        // 1. Force a PRO subscription manually
        transactionTemplate.executeWithoutResult(status -> {
            TenantSubscription sub = new TenantSubscription();
            sub.setTenant(testTenant);
            sub.setPlan(subscriptionPlanRepository.findById("PRO").get());
            sub.setStatus(TenantSubscription.SubscriptionStatus.PAST_DUE);
            sub.setBillingCycle(TenantSubscription.BillingCycle.MONTHLY);
            // Expired 1 day ago
            sub.setCurrentPeriodStart(LocalDateTime.now().minusMonths(1).minusDays(1));
            sub.setCurrentPeriodEnd(LocalDateTime.now().minusDays(1));
            tenantSubscriptionRepository.save(sub);
        });

        // 2. Fetch from cache -> should return PRO because the cache fetches from DB without running QuotaEnforcer rules
        assertEquals(User.PlanType.PRO, tenantTierService.getTier(testTenant.getId()));

        // 3. QuotaEnforcer intercepts an action and realizes it's expired, downgrading to FREE
        transactionTemplate.executeWithoutResult(status -> {
            quotaEnforcerService.getActiveSubscription(testTenant.getId());
        });

        // 4. Cache should have been invalidated by QuotaEnforcer, so next lookup is FREE
        assertEquals(User.PlanType.FREE, tenantTierService.getTier(testTenant.getId()));
    }

    @Test
    void testAdminPlanChange_InvalidatesCache() {
        // 1. Initial cache
        assertEquals(User.PlanType.FREE, tenantTierService.getTier(testTenant.getId()));

        // 2. Admin directly saves to DB and publishes event
        transactionTemplate.executeWithoutResult(status -> {
            TenantSubscription sub = tenantSubscriptionRepository.findByTenantId(testTenant.getId()).orElse(new TenantSubscription());
            sub.setTenant(testTenant);
            sub.setPlan(subscriptionPlanRepository.findById("PRO").get());
            sub.setStatus(TenantSubscription.SubscriptionStatus.ACTIVE);
            sub.setCurrentPeriodEnd(LocalDateTime.now().plusMonths(1));
            sub.setCurrentPeriodStart(LocalDateTime.now());
            sub.setBillingCycle(TenantSubscription.BillingCycle.MONTHLY);
            tenantSubscriptionRepository.save(sub);
            
            // Simulating an admin service firing the event
            eventPublisher.publishEvent(new com.chatcrmlite.backend.event.TenantSubscriptionUpdatedEvent(this, testTenant.getId()));
        });

        // 3. Verify cache is PRO
        assertEquals(User.PlanType.PRO, tenantTierService.getTier(testTenant.getId()));
    }
}
