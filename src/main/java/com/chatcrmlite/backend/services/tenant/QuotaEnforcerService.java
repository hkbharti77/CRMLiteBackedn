package com.chatcrmlite.backend.services.tenant;

import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.models.TenantSubscription.SubscriptionStatus;
import com.chatcrmlite.backend.repositories.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import com.chatcrmlite.backend.event.TenantSubscriptionUpdatedEvent;
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
    private final ApplicationEventPublisher eventPublisher;
    private final EntitlementResolverService entitlementResolverService;

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
            Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
            if (tenant == null) {
                log.warn("⚠️ Cannot initialize FREE plan — tenant {} does not exist in the tenants table.", tenantId);
                throw new IllegalStateException("Tenant not found: " + tenantId + ". Cannot initialize subscription.");
            }

            log.info("ℹ️ No subscription found for tenant: {}. Initializing FREE plan.", tenantId);
            SubscriptionPlan freePlan = subscriptionPlanRepository.findById("FREE")
                    .orElseGet(() -> {
                        SubscriptionPlan plan = new SubscriptionPlan();
                        plan.setId("FREE");
                        plan.setName("Free Starter Pack");
                        plan.setPriceMonthly(java.math.BigDecimal.ZERO);
                        plan.setPriceYearly(java.math.BigDecimal.ZERO);
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
                    .currentPeriodEnd(LocalDateTime.now().plusYears(10))
                    .tenant(tenant)
                    .build();

            sub = tenantSubscriptionRepository.save(sub);
            eventPublisher.publishEvent(new TenantSubscriptionUpdatedEvent(this, tenantId));
        }

        if (sub.getStatus() == SubscriptionStatus.PAST_DUE || 
            sub.getStatus() == SubscriptionStatus.CANCELLED || 
            sub.getStatus() == SubscriptionStatus.INACTIVE) {
            
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
                eventPublisher.publishEvent(new TenantSubscriptionUpdatedEvent(this, tenantId));
            }
        }

        return sub;
    }

    /**
     * Checks if tenant has room for another employee seat using Effective Entitlements.
     */
    public void verifyEmployeeSeatQuota(UUID tenantId) {
        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        long activeUsersCount = userRepository.countByTenantId(tenantId);
        int limit = entitlements.getLimits().getEmployeeLimit();
        
        if (activeUsersCount >= limit) {
            log.warn("⚠️ Tenant {} has reached employee limit ({} / {})", tenantId, activeUsersCount, limit);
            throw new QuotaExceededException("Employee limit reached (" + limit + " seats max) for your current plan. Please upgrade to Pro.");
        }
    }

    /**
     * Checks if tenant can add more leads using Effective Entitlements.
     */
    public void verifyLeadQuota(UUID tenantId) {
        TenantSubscription sub = getActiveSubscription(tenantId);
        com.chatcrmlite.backend.models.Tenant tenant = sub.getTenant();
        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        long leadCount = leadRepository.countByTenantId(tenantId);

        int limit = (tenant.getPrimaryResource() == com.chatcrmlite.backend.models.Tenant.PrimaryResource.LEAD) ? 
                entitlements.getLimits().getPrimaryResourceLimit() : 
                entitlements.getLimits().getSecondaryResourceLimit();

        if (leadCount >= limit) {
            log.warn("⚠️ Tenant {} has reached lead storage limit ({} / {})", tenantId, leadCount, limit);
            throw new QuotaExceededException("Lead storage limit reached (" + limit + " leads max) for your current plan. Please upgrade to Pro.");
        }
    }

    /**
     * Checks if tenant can schedule another booking/appointment using Effective Entitlements.
     */
    public void verifyBookingQuota(UUID tenantId) {
        TenantSubscription sub = getActiveSubscription(tenantId);
        com.chatcrmlite.backend.models.Tenant tenant = sub.getTenant();
        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        
        long bookingCount = bookingRepository.countByTenantId(tenantId);
        long appointmentCount = appointmentRepository.countByTenantId(tenantId);
        long totalSchedules = bookingCount + appointmentCount;

        int limit = (tenant.getPrimaryResource() == com.chatcrmlite.backend.models.Tenant.PrimaryResource.BOOKING || 
                     tenant.getPrimaryResource() == com.chatcrmlite.backend.models.Tenant.PrimaryResource.APPOINTMENT) ? 
                entitlements.getLimits().getPrimaryResourceLimit() : 
                entitlements.getLimits().getSecondaryResourceLimit();

        if (totalSchedules >= limit) {
            log.warn("⚠️ Tenant {} has reached scheduling limit ({} / {})", tenantId, totalSchedules, limit);
            throw new QuotaExceededException("Scheduling slots limit reached (" + limit + " monthly slots max) for your current plan. Please upgrade to Pro.");
        }
    }

    /**
     * Checks if tenant can open more support tickets using Effective Entitlements.
     */
    public void verifyTicketQuota(UUID tenantId) {
        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        long ticketCount = ticketRepository.countActiveByTenantId(tenantId);
        int limit = entitlements.getLimits().getTicketLimit();

        if (ticketCount >= limit) {
            log.warn("⚠️ Tenant {} has reached ticket limit ({} / {})", tenantId, ticketCount, limit);
            throw new QuotaExceededException("Active support tickets limit reached (" + limit + " tickets max) for your current plan. Please upgrade to Pro.");
        }
    }

    /**
     * Checks if tenant has remaining email campaign quota using Effective Entitlements.
     */
    public void verifyEmailCampaignQuota(UUID tenantId, int recipientCount) {
        TenantSubscription sub = getActiveSubscription(tenantId);
        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        
        LocalDateTime cycleStart = sub.getCurrentPeriodStart();
        long emailsSentThisCycle = customEmailRepository.countSentEmailsSince(tenantId, cycleStart);
        int limit = entitlements.getLimits().getEmailLimit();

        if ((emailsSentThisCycle + recipientCount) > limit) {
            log.warn("⚠️ Tenant {} has insufficient email campaign credits (sent: {}, sending: {}, limit: {})", 
                    tenantId, emailsSentThisCycle, recipientCount, limit);
            throw new QuotaExceededException("Insufficient email campaign credits remaining this cycle. (Limit: " + 
                    limit + ", already sent: " + emailsSentThisCycle + "). Please upgrade to Pro.");
        }
    }

    /**
     * Checks if WhatsApp Meta API integration is enabled for this tier using Effective Entitlements.
     */
    public void verifyWhatsAppIntegrationAllowed(UUID tenantId) {
        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        if (!entitlements.getFeatures().isHasWhatsapp()) {
            log.warn("⚠️ Tenant {} tried to access WhatsApp which is disabled", tenantId);
            throw new QuotaExceededException("WhatsApp API Business Integration is not available on your current plan. Please upgrade to Pro.");
        }
    }

    /**
     * Checks if WhatsApp Campaign feature is enabled and verifies campaign volume quota using Effective Entitlements.
     */
    public void verifyWhatsAppCampaignQuota(UUID tenantId, int recipientCount) {
        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        if (!entitlements.getFeatures().isHasWhatsappCampaign()) {
            log.warn("⚠️ Tenant {} tried to send WhatsApp Campaign which is disabled", tenantId);
            throw new QuotaExceededException("WhatsApp Campaigns & Broadcasts feature is not available on your current plan. Please upgrade your plan.");
        }

        int maxRecips = entitlements.getLimits().getMaxRecipientsPerWhatsappCampaign();
        if (recipientCount > maxRecips) {
            log.warn("⚠️ Tenant {} campaign audience ({}) exceeds max campaign recipient limit ({})", tenantId, recipientCount, maxRecips);
            throw new QuotaExceededException("Campaign recipient count (" + recipientCount + ") exceeds your plan's maximum WhatsApp campaign limit (" + maxRecips + "). Please upgrade your plan.");
        }
    }

    /**
     * Checks if Custom Branding & white labeling is enabled for this tier using Effective Entitlements.
     */
    public boolean isCustomWidgetBrandingAllowed(UUID tenantId) {
        try {
            com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
            return entitlements.getFeatures().isHasCustomWidget();
        } catch (Exception e) {
            return false;
        }
    }
}
