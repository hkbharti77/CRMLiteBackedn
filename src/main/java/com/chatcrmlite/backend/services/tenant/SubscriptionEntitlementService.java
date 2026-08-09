package com.chatcrmlite.backend.services.tenant;

import com.chatcrmlite.backend.exceptions.SubscriptionFeatureException;
import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.repositories.TenantSubscriptionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionEntitlementService {

    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final WhatsAppCampaignRepository campaignRepository;
    private final QuotaEnforcerService quotaEnforcerService;
    private final EntitlementResolverService entitlementResolverService;

    public List<WhatsAppCampaign.Priority> getAllowedPriorities(UUID tenantId) {
        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        return entitlements.getAllowedPriorities();
    }

    public boolean isCampaignPriorityAllowed(UUID tenantId, WhatsAppCampaign.Priority priority) {
        if (priority == null || priority == WhatsAppCampaign.Priority.LOW) {
            return true;
        }
        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        return entitlements.getAllowedPriorities().contains(priority);
    }

    public void verifyCampaignPriorityAllowed(UUID tenantId, WhatsAppCampaign.Priority priority) {
        if (!isCampaignPriorityAllowed(tenantId, priority)) {
            com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
            List<String> allowedNames = entitlements.getAllowedPriorities().stream().map(Enum::name).toList();

            log.warn("⚠️ Tenant {} requested priority {} which is not allowed on plan {}", tenantId, priority, entitlements.getBasePlanId());

            throw new SubscriptionFeatureException(
                    "CAMPAIGN_PRIORITY_NOT_ALLOWED",
                    "WhatsApp Campaign Queue Priority",
                    entitlements.getBasePlanId(),
                    allowedNames,
                    "Campaign priority '" + priority + "' is not permitted on your current entitlement configuration. Allowed priorities: " + allowedNames + ". Upgrade your subscription for higher queue priorities."
            );
        }
    }

    @Transactional
    public void revalidateTenantCampaignPriorities(UUID tenantId) {
        com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId);
        List<WhatsAppCampaign.Priority> allowed = entitlements.getAllowedPriorities();

        WhatsAppCampaign.Priority maxAllowed = entitlements.getMaxAllowedPriority();

        List<WhatsAppCampaign> tenantCampaigns = campaignRepository.findAll().stream()
                .filter(c -> c.getTenant() != null && c.getTenant().getId().equals(tenantId))
                .filter(c -> c.getStatus() == WhatsAppCampaign.Status.DRAFT || c.getStatus() == WhatsAppCampaign.Status.SCHEDULED)
                .filter(c -> !Boolean.TRUE.equals(c.getPriorityLocked()))
                .toList();

        for (WhatsAppCampaign campaign : tenantCampaigns) {
            if (!allowed.contains(campaign.getPriority())) {
                log.info("ℹ️ Auto-adjusting campaignId={} priority from {} to {} due to tenant plan change",
                        campaign.getId(), campaign.getPriority(), maxAllowed);
                campaign.setPriority(maxAllowed);
                campaign.setPriorityRank(maxAllowed.getRank());
                campaignRepository.save(campaign);
            }
        }
    }
}
