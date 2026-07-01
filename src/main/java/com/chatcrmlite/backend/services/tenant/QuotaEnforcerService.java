package com.chatcrmlite.backend.services.tenant;

import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.repositories.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaEnforcerService {

    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final LeadRepository leadRepository;
    private final BookingRepository bookingRepository;
    private final AppointmentRepository appointmentRepository;
    private final TicketRepository ticketRepository;
    private final CustomEmailRepository customEmailRepository;

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }

    public static class SubscriptionExpiredException extends RuntimeException {
        public SubscriptionExpiredException(String message) {
            super(message);
        }
    }

    /**
     * Resolves the active subscription plan and verifies subscription state.
     * If no subscription is configured, it registers a default FREE subscription.
     */
    public TenantSubscription getActiveSubscription(UUID tenantId) {
        TenantSubscription sub = tenantSubscriptionRepository.findByTenantId(tenantId).orElse(null);

        if (sub == null) {
            // Guard: verify tenant exists in DB before attempting to insert subscription.
            // A missing tenant row would violate the FK constraint on tenant_subscriptions.
            Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
            if (tenant == null) {
                log.warn("⚠️ Cannot initialize FREE plan — tenant {} does not exist in the tenants table. " +
                         "The tenant may not have been fully registered yet.", tenantId);
                throw new IllegalStateException(
                        "Tenant not found: " + tenantId + ". Cannot initialize subscription.");
            }

            log.info("ℹ️ No subscription found for tenant: {}. Initializing FREE plan.", tenantId);
            SubscriptionPlan freePlan = subscriptionPlanRepository.findById("FREE")
                    .orElseGet(() -> {
                        // Fallback if DB not seeded yet
                        SubscriptionPlan plan = new SubscriptionPlan();
                        plan.setId("FREE");
                        plan.setName("Free Starter Pack");
                        plan.setEmployeeLimit(1);
                        plan.setPrimaryResourceLimit(100);
                        plan.setSecondaryResourceLimit(15);
                        plan.setTicketLimit(10);
                        plan.setEmailLimit(500);
                        plan.setHasWhatsapp(false);
                        plan.setHasCustomWidget(false);
                        return subscriptionPlanRepository.save(plan);
                    });

            sub = TenantSubscription.builder()
                    .plan(freePlan)
                    .status(SubscriptionStatus.FREE_TRIAL)
                    .billingCycle(TenantSubscription.BillingCycle.MONTHLY)
                    .currentPeriodStart(LocalDateTime.now())
                    .currentPeriodEnd(LocalDateTime.now().plusYears(10)) // Generous trial window
                    .tenant(tenant)
                    .build();

            sub = tenantSubscriptionRepository.save(sub);
        }

        // Validate subscription status
        if (sub.getStatus() == SubscriptionStatus.PAST_DUE || 
            sub.getStatus() == SubscriptionStatus.CANCELLED || 
            sub.getStatus() == SubscriptionStatus.INACTIVE) {
            
            // Check if period end has passed
            if (sub.getCurrentPeriodEnd().isBefore(LocalDateTime.now())) {
                log.warn("Subscription for tenant {} is expired (status: {}, end: {}). Auto-downgrading to FREE.", 
                        tenantId, sub.getStatus(), sub.getCurrentPeriodEnd());
                
                SubscriptionPlan freePlan = subscriptionPlanRepository.findById("FREE").orElseThrow();
                sub.setPlan(freePlan);
                sub.setStatus(SubscriptionStatus.FREE_TRIAL);
                sub.setBillingCycle(TenantSubscription.BillingCycle.MONTHLY);
                sub.setCurrentPeriodStart(LocalDateTime.now());
                sub.setCurrentPeriodEnd(LocalDateTime.now().plusYears(10));
                sub = tenantSubscriptionRepository.save(sub);
            }
        }

        return sub;
    }

    /**
     * Checks if tenant has room for another employee seat.
     */
    public void verifyEmployeeSeatQuota(UUID tenantId) {
        TenantSubscription sub = getActiveSubscription(tenantId);
        long activeUsersCount = userRepository.countByTenantId(tenantId);
        
        // Note: owner count is included in seats.
        if (activeUsersCount >= sub.getPlan().getEmployeeLimit()) {
            log.warn("⚠️ Tenant {} has reached employee limit ({} / {})", 
                    tenantId, activeUsersCount, sub.getPlan().getEmployeeLimit());
            throw new QuotaExceededException("Employee limit reached (" + sub.getPlan().getEmployeeLimit() + " seats max) for your current plan. Please upgrade to Pro.");
        }
    }

    /**
     * Checks if tenant can add more leads.
     */
    public void verifyLeadQuota(UUID tenantId) {
        TenantSubscription sub = getActiveSubscription(tenantId);
        com.chatcrmlite.backend.models.Tenant tenant = sub.getTenant();
        long leadCount = leadRepository.countByTenantId(tenantId);

        int limit = (tenant.getPrimaryResource() == com.chatcrmlite.backend.models.Tenant.PrimaryResource.LEAD) ? 
                sub.getPlan().getPrimaryResourceLimit() : 
                sub.getPlan().getSecondaryResourceLimit();

        if (leadCount >= limit) {
            log.warn("⚠️ Tenant {} has reached lead storage limit ({} / {})", 
                    tenantId, leadCount, limit);
            throw new QuotaExceededException("Lead storage limit reached (" + limit + " leads max) for your current plan. Please upgrade to Pro.");
        }
    }

    /**
     * Checks if tenant can schedule another booking/appointment this month.
     */
    public void verifyBookingQuota(UUID tenantId) {
        TenantSubscription sub = getActiveSubscription(tenantId);
        com.chatcrmlite.backend.models.Tenant tenant = sub.getTenant();
        
        // Count bookings and appointments combined
        long bookingCount = bookingRepository.countByTenantId(tenantId);
        long appointmentCount = appointmentRepository.countByTenantId(tenantId);
        long totalSchedules = bookingCount + appointmentCount;

        int limit = (tenant.getPrimaryResource() == com.chatcrmlite.backend.models.Tenant.PrimaryResource.BOOKING || 
                     tenant.getPrimaryResource() == com.chatcrmlite.backend.models.Tenant.PrimaryResource.APPOINTMENT) ? 
                sub.getPlan().getPrimaryResourceLimit() : 
                sub.getPlan().getSecondaryResourceLimit();

        if (totalSchedules >= limit) {
            log.warn("⚠️ Tenant {} has reached scheduling limit ({} / {})", 
                    tenantId, totalSchedules, limit);
            throw new QuotaExceededException("Scheduling slots limit reached (" + limit + " monthly slots max) for your current plan. Please upgrade to Pro.");
        }
    }

    /**
     * Checks if tenant can open more support tickets.
     */
    public void verifyTicketQuota(UUID tenantId) {
        TenantSubscription sub = getActiveSubscription(tenantId);
        long ticketCount = ticketRepository.countActiveByTenantId(tenantId);

        if (ticketCount >= sub.getPlan().getTicketLimit()) {
            log.warn("⚠️ Tenant {} has reached ticket limit ({} / {})", 
                    tenantId, ticketCount, sub.getPlan().getTicketLimit());
            throw new QuotaExceededException("Active support tickets limit reached (" + sub.getPlan().getTicketLimit() + " tickets max) for your current plan. Please upgrade to Pro.");
        }
    }

    /**
     * Checks if tenant has remaining email campaign quota.
     */
    public void verifyEmailCampaignQuota(UUID tenantId, int recipientCount) {
        TenantSubscription sub = getActiveSubscription(tenantId);
        
        // Determine start of current billing cycle
        LocalDateTime cycleStart = sub.getCurrentPeriodStart();
        long emailsSentThisCycle = customEmailRepository.countSentEmailsSince(tenantId, cycleStart);

        if ((emailsSentThisCycle + recipientCount) > sub.getPlan().getEmailLimit()) {
            log.warn("⚠️ Tenant {} has insufficient email campaign credits (sent: {}, sending: {}, limit: {})", 
                    tenantId, emailsSentThisCycle, recipientCount, sub.getPlan().getEmailLimit());
            throw new QuotaExceededException("Insufficient email campaign credits remaining this cycle. (Limit: " + 
                    sub.getPlan().getEmailLimit() + ", already sent: " + emailsSentThisCycle + "). Please upgrade to Pro.");
        }
    }

    /**
     * Checks if WhatsApp Meta API integration is enabled for this tier.
     */
    public void verifyWhatsAppIntegrationAllowed(UUID tenantId) {
        TenantSubscription sub = getActiveSubscription(tenantId);
        if (!sub.getPlan().isHasWhatsapp()) {
            log.warn("⚠️ Tenant {} tried to access WhatsApp which is disabled on plan {}", tenantId, sub.getPlan().getId());
            throw new QuotaExceededException("WhatsApp API Business Integration is not available on your current plan. Please upgrade to Pro.");
        }
    }

    /**
     * Checks if Custom Branding & white labeling is enabled for this tier.
     */
    public boolean isCustomWidgetBrandingAllowed(UUID tenantId) {
        try {
            TenantSubscription sub = getActiveSubscription(tenantId);
            return sub.getPlan().isHasCustomWidget();
        } catch (Exception e) {
            return false;
        }
    }
}
