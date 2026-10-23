package com.chatcrmlite.backend.services.billing;

import com.chatcrmlite.backend.event.TenantSubscriptionUpdatedEvent;
import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.SubscriptionPlanRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.TenantSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled worker that scans for expired paid subscriptions and automatically
 * downgrades them to the FREE plan. This guarantees that tenants who do not renew
 * cannot continue utilizing high-tier capabilities (AI voice bots, outbound calling,
 * broadcast campaigns, excess staff seats) at the platform owner's expense.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryWorker {

    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final TenantRepository tenantRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 300000, initialDelay = 30000) // Runs every 5 minutes
    @Transactional
    public void scanAndDowngradeExpiredSubscriptions() {
        LocalDateTime now = LocalDateTime.now();
        List<TenantSubscription> expiredSubs = tenantSubscriptionRepository.findAllExpiredPaidSubscriptions(now);

        if (expiredSubs == null || expiredSubs.isEmpty()) {
            return;
        }

        log.info("⏰ [SubscriptionExpiryWorker] Found {} expired paid subscription(s) to downgrade.", expiredSubs.size());
        SubscriptionPlan freePlan = subscriptionPlanRepository.findById("FREE")
                .orElseGet(() -> {
                    SubscriptionPlan fp = new SubscriptionPlan();
                    fp.setId("FREE");
                    fp.setName("Free Plan");
                    fp.setPriceMonthly(java.math.BigDecimal.ZERO);
                    fp.setPriceYearly(java.math.BigDecimal.ZERO);
                    fp.setEmployeeLimit(1);
                    fp.setPrimaryResourceLimit(100);
                    fp.setSecondaryResourceLimit(15);
                    fp.setTicketLimit(10);
                    fp.setEmailLimit(500);
                    return subscriptionPlanRepository.save(fp);
                });

        for (TenantSubscription sub : expiredSubs) {
            try {
                UUID tenantId = sub.getTenant().getId();
                String expiredPlanId = sub.getPlan() != null ? sub.getPlan().getId() : "UNKNOWN";
                LocalDateTime endedAt = sub.getCurrentPeriodEnd();

                log.warn("🔻 [EXPIRED] Auto-downgrading tenant {} from plan '{}' (expired at: {}) to FREE.", 
                        tenantId, expiredPlanId, endedAt);

                sub.setPlan(freePlan);
                sub.setStatus(SubscriptionStatus.ACTIVE);
                sub.setBillingCycle(TenantSubscription.BillingCycle.MONTHLY);
                sub.setCurrentPeriodStart(now);
                sub.setCurrentPeriodEnd(now.plusYears(10));
                tenantSubscriptionRepository.save(sub);

                // Sync legacy tenant.planType
                try {
                    Tenant t = tenantRepository.findById(tenantId).orElse(null);
                    if (t != null) {
                        t.setPlanType(User.PlanType.FREE);
                        tenantRepository.save(t);
                    }
                } catch (Exception ex) {
                    log.warn("⚠️ Failed syncing tenant planType for tenant {}: {}", tenantId, ex.getMessage());
                }

                // Invalidate Redis cache
                eventPublisher.publishEvent(new TenantSubscriptionUpdatedEvent(this, tenantId));
                log.info("✅ Downgrade to FREE completed for tenant {}.", tenantId);
            } catch (Exception e) {
                log.error("❌ Error auto-downgrading subscription ID {}: {}", sub.getId(), e.getMessage(), e);
            }
        }
    }
}
