package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.TenantSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, UUID> {

    @Query("SELECT ts FROM TenantSubscription ts WHERE ts.tenant.id = :tenantId")
    Optional<TenantSubscription> findByTenantId(@Param("tenantId") UUID tenantId);

    Optional<TenantSubscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    Optional<TenantSubscription> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    @Query("SELECT ts FROM TenantSubscription ts WHERE ts.plan.id <> 'FREE' AND ts.currentPeriodEnd IS NOT NULL AND ts.currentPeriodEnd < :now")
    java.util.List<TenantSubscription> findAllExpiredPaidSubscriptions(@Param("now") java.time.LocalDateTime now);
}
