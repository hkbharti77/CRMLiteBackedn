package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO;
import com.chatcrmlite.backend.dtos.UpdateTenantOverridesRequestDto;
import com.chatcrmlite.backend.models.PlatformAdmin;
import com.chatcrmlite.backend.models.TenantSubscriptionOverride;
import com.chatcrmlite.backend.models.TenantSubscriptionOverrideAudit;
import com.chatcrmlite.backend.repositories.PlatformAdminRepository;
import com.chatcrmlite.backend.repositories.TenantSubscriptionOverrideAuditRepository;
import com.chatcrmlite.backend.repositories.TenantSubscriptionOverrideRepository;
import com.chatcrmlite.backend.services.tenant.EntitlementResolverService;
import com.chatcrmlite.backend.services.tenant.SubscriptionEntitlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/platform/tenants/{tenantId}")
@RequiredArgsConstructor
public class PlatformTenantOverrideController {

    private final EntitlementResolverService entitlementResolverService;
    private final SubscriptionEntitlementService subscriptionEntitlementService;
    private final TenantSubscriptionOverrideRepository overrideRepository;
    private final TenantSubscriptionOverrideAuditRepository auditRepository;
    private final PlatformAdminRepository platformAdminRepository;
    private final com.chatcrmlite.backend.repositories.TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    private String verifyPlatformAdminAccess(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication principal missing");
        }
        PlatformAdmin admin = platformAdminRepository.findByEmail(email).orElse(null);
        if (admin == null) {
            log.warn("⚠️ Unauthorized tenant override access attempt by user: {}", email);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access restricted to Platform Admins only");
        }
        return admin.getEmail();
    }

    private com.chatcrmlite.backend.models.Tenant resolveTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId).orElseGet(() -> {
            com.chatcrmlite.backend.models.Tenant t = new com.chatcrmlite.backend.models.Tenant();
            t.setId(tenantId);
            return t;
        });
    }

    @GetMapping("/entitlements")
    public ResponseEntity<EffectiveEntitlementsDTO> getEntitlements(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "false") boolean trace,
            @AuthenticationPrincipal String email) {
        verifyPlatformAdminAccess(email);
        EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId, trace);
        return ResponseEntity.ok(entitlements);
    }

    @PutMapping("/overrides")
    @Transactional
    public ResponseEntity<?> updateTenantOverrides(
            @PathVariable UUID tenantId,
            @RequestBody UpdateTenantOverridesRequestDto requestDto,
            @AuthenticationPrincipal String email,
            HttpServletRequest servletRequest) {

        String adminEmail = verifyPlatformAdminAccess(email);
        com.chatcrmlite.backend.models.Tenant tenant = resolveTenant(tenantId);

        TenantSubscriptionOverride existingOverride = overrideRepository.findByTenantId(tenantId).orElse(null);
        String oldValueJson = existingOverride != null ? serializeOverride(existingOverride) : null;

        TenantSubscriptionOverride overrideToSave = existingOverride != null ? existingOverride : new TenantSubscriptionOverride();
        overrideToSave.setTenant(tenant);

        // Build feature overrides JSON
        Map<String, Object> featureMap = new HashMap<>();
        if (requestDto.getHasWhatsapp() != null) featureMap.put("hasWhatsapp", requestDto.getHasWhatsapp());
        if (requestDto.getHasWhatsappCampaign() != null) featureMap.put("hasWhatsappCampaign", requestDto.getHasWhatsappCampaign());
        if (requestDto.getHasCustomWidget() != null) featureMap.put("hasCustomWidget", requestDto.getHasCustomWidget());
        if (requestDto.getHasRagLlm() != null) featureMap.put("hasRagLlm", requestDto.getHasRagLlm());
        if (requestDto.getHasEmailCampaign() != null) featureMap.put("hasEmailCampaign", requestDto.getHasEmailCampaign());
        overrideToSave.setFeatureOverrides(serializeMap(featureMap));

        // Build quota overrides JSON
        Map<String, Object> quotaMap = new HashMap<>();
        if (requestDto.getEmployeeLimit() != null) quotaMap.put("employeeLimit", requestDto.getEmployeeLimit());
        if (requestDto.getPrimaryResourceLimit() != null) quotaMap.put("primaryResourceLimit", requestDto.getPrimaryResourceLimit());
        if (requestDto.getSecondaryResourceLimit() != null) quotaMap.put("secondaryResourceLimit", requestDto.getSecondaryResourceLimit());
        if (requestDto.getTicketLimit() != null) quotaMap.put("ticketLimit", requestDto.getTicketLimit());
        if (requestDto.getEmailLimit() != null) quotaMap.put("emailLimit", requestDto.getEmailLimit());
        if (requestDto.getMaxRecipientsPerWhatsappCampaign() != null) quotaMap.put("maxRecipientsPerWhatsappCampaign", requestDto.getMaxRecipientsPerWhatsappCampaign());
        if (requestDto.getMonthlyWhatsappMessageQuota() != null) quotaMap.put("monthlyWhatsappMessageQuota", requestDto.getMonthlyWhatsappMessageQuota());
        overrideToSave.setQuotaOverrides(serializeMap(quotaMap));

        // Build priority overrides JSON
        if (requestDto.getMaxAllowedPriority() != null) {
            overrideToSave.setPriorityOverrides(serializeMap(Map.of("maxPriority", requestDto.getMaxAllowedPriority().name())));
        }

        // Build pricing overrides JSON
        Map<String, Object> priceMap = new HashMap<>();
        if (requestDto.getCustomMonthlyInr() != null) priceMap.put("monthlyInr", requestDto.getCustomMonthlyInr());
        if (requestDto.getCustomYearlyInr() != null) priceMap.put("yearlyInr", requestDto.getCustomYearlyInr());
        if (requestDto.getCustomMonthlyUsd() != null) priceMap.put("monthlyUsd", requestDto.getCustomMonthlyUsd());
        if (requestDto.getCustomYearlyUsd() != null) priceMap.put("yearlyUsd", requestDto.getCustomYearlyUsd());
        overrideToSave.setPricingOverrides(serializeMap(priceMap));

        if (requestDto.getEffectiveFrom() != null) overrideToSave.setEffectiveFrom(requestDto.getEffectiveFrom());
        if (requestDto.getEffectiveUntil() != null) overrideToSave.setEffectiveUntil(requestDto.getEffectiveUntil());

        overrideToSave.setVersion(overrideToSave.getVersion() != null ? overrideToSave.getVersion() + 1 : 1);
        overrideToSave.setUpdatedBy(adminEmail);
        if (existingOverride == null) {
            overrideToSave.setCreatedBy(adminEmail);
        }

        TenantSubscriptionOverride saved;
        try {
            saved = overrideRepository.save(overrideToSave);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("⚠️ Optimistic locking conflict updating overrides for tenantId={}", tenantId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Tenant overrides were updated by another administrator. Please refresh and try again.", "code", "OPTIMISTIC_LOCK_CONFLICT"));
        }

        String newValueJson = serializeOverride(saved);

        // Record Audit Entry
        TenantSubscriptionOverrideAudit audit = TenantSubscriptionOverrideAudit.builder()
                .action(existingOverride == null ? TenantSubscriptionOverrideAudit.OverrideAuditAction.CREATE_OVERRIDE : TenantSubscriptionOverrideAudit.OverrideAuditAction.UPDATE_OVERRIDE)
                .oldValueJson(oldValueJson)
                .newValueJson(newValueJson)
                .changedBy(adminEmail)
                .reason(requestDto.getReason() != null ? requestDto.getReason() : "Platform Admin Custom Overrides Update")
                .requestId(servletRequest.getHeader("X-Request-ID"))
                .ipAddress(servletRequest.getRemoteAddr())
                .build();
        audit.setTenant(tenant);
        auditRepository.save(audit);

        // Invalidate Redis pointer cache
        entitlementResolverService.invalidateEntitlementsCache(tenantId);
        subscriptionEntitlementService.revalidateTenantCampaignPriorities(tenantId);

        log.info("✅ Platform admin {} updated overrides for tenantId={} (version={})", adminEmail, tenantId, saved.getVersion());
        return ResponseEntity.ok(entitlementResolverService.getEffectiveEntitlements(tenantId, true));
    }

    @DeleteMapping("/overrides")
    @Transactional
    public ResponseEntity<Map<String, String>> resetTenantOverrides(
            @PathVariable UUID tenantId,
            @AuthenticationPrincipal String email,
            HttpServletRequest servletRequest) {

        String adminEmail = verifyPlatformAdminAccess(email);
        com.chatcrmlite.backend.models.Tenant tenant = resolveTenant(tenantId);
        TenantSubscriptionOverride existing = overrideRepository.findByTenantId(tenantId).orElse(null);

        if (existing != null) {
            String oldValue = serializeOverride(existing);
            overrideRepository.deleteByTenantId(tenantId);

            TenantSubscriptionOverrideAudit audit = TenantSubscriptionOverrideAudit.builder()
                    .action(TenantSubscriptionOverrideAudit.OverrideAuditAction.RESET_OVERRIDE)
                    .oldValueJson(oldValue)
                    .newValueJson(null)
                    .changedBy(adminEmail)
                    .reason("Reset tenant custom overrides to standard base plan defaults")
                    .requestId(servletRequest.getHeader("X-Request-ID"))
                    .ipAddress(servletRequest.getRemoteAddr())
                    .build();
            audit.setTenant(tenant);
            auditRepository.save(audit);

            entitlementResolverService.invalidateEntitlementsCache(tenantId);
            subscriptionEntitlementService.revalidateTenantCampaignPriorities(tenantId);
            log.info("🧹 Platform admin {} reset overrides for tenantId={}", adminEmail, tenantId);
        }

        return ResponseEntity.ok(Map.of("message", "Tenant custom overrides reset to base plan defaults successfully. Existing usage preserved."));
    }

    @GetMapping("/override-audits")
    public ResponseEntity<List<TenantSubscriptionOverrideAudit>> getOverrideAudits(
            @PathVariable UUID tenantId,
            @AuthenticationPrincipal String email) {
        verifyPlatformAdminAccess(email);
        return ResponseEntity.ok(auditRepository.findByTenantIdOrderByCreatedAtDesc(tenantId));
    }

    private String serializeOverride(TenantSubscriptionOverride override) {
        try {
            return objectMapper.writeValueAsString(override);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String serializeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }
}
