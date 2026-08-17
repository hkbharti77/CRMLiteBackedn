package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO;
import com.chatcrmlite.backend.dtos.UpdateTenantOverridesRequestDto;
import com.chatcrmlite.backend.dtos.entitlements.PlatformTenantEntitlementMatrixDTO;
import com.chatcrmlite.backend.dtos.entitlements.UpdateTenantEntitlementsMatrixRequestDto;
import com.chatcrmlite.backend.models.PlatformAdmin;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantSubscriptionOverride;
import com.chatcrmlite.backend.models.TenantSubscriptionOverrideAudit;
import com.chatcrmlite.backend.models.entitlements.EntitlementCatalog;
import com.chatcrmlite.backend.models.entitlements.EntitlementDefinition;
import com.chatcrmlite.backend.models.entitlements.EntitlementMutability;
import com.chatcrmlite.backend.models.entitlements.OverrideAction;
import com.chatcrmlite.backend.repositories.PlatformAdminRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.TenantSubscriptionOverrideAuditRepository;
import com.chatcrmlite.backend.repositories.TenantSubscriptionOverrideRepository;
import com.chatcrmlite.backend.services.platform.PlatformAuditService;
import com.chatcrmlite.backend.services.platform.PlatformEntitlementPresetService;
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
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
public class PlatformTenantOverrideController {

    private final EntitlementResolverService entitlementResolverService;
    private final SubscriptionEntitlementService subscriptionEntitlementService;
    private final TenantSubscriptionOverrideRepository overrideRepository;
    private final TenantSubscriptionOverrideAuditRepository auditRepository;
    private final PlatformAdminRepository platformAdminRepository;
    private final TenantRepository tenantRepository;
    private final PlatformEntitlementPresetService presetService;
    private final PlatformAuditService platformAuditService;
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

    private Tenant resolveTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId).orElseGet(() -> {
            Tenant t = new Tenant();
            t.setId(tenantId);
            return t;
        });
    }

    @GetMapping("/entitlement-presets")
    public ResponseEntity<List<PlatformEntitlementPresetService.PresetDefinition>> getPresets(
            @AuthenticationPrincipal String email) {
        verifyPlatformAdminAccess(email);
        return ResponseEntity.ok(presetService.getAvailablePresets());
    }

    @GetMapping("/tenants/{tenantId}/entitlements-matrix")
    public ResponseEntity<PlatformTenantEntitlementMatrixDTO> getEntitlementsMatrix(
            @PathVariable UUID tenantId,
            @AuthenticationPrincipal String email) {
        verifyPlatformAdminAccess(email);
        PlatformTenantEntitlementMatrixDTO matrix = entitlementResolverService.getPlatformTenantEntitlementMatrix(tenantId);
        return ResponseEntity.ok(matrix);
    }

    @PutMapping("/tenants/{tenantId}/entitlements-matrix")
    @Transactional
    public ResponseEntity<?> updateEntitlementsMatrix(
            @PathVariable UUID tenantId,
            @RequestBody UpdateTenantEntitlementsMatrixRequestDto requestDto,
            @AuthenticationPrincipal String email,
            HttpServletRequest servletRequest) {

        String adminEmail = verifyPlatformAdminAccess(email);
        Tenant tenant = resolveTenant(tenantId);

        TenantSubscriptionOverride existingOverride = overrideRepository.findByTenantId(tenantId).orElse(null);
        TenantSubscriptionOverride overrideToSave = existingOverride != null ? existingOverride : new TenantSubscriptionOverride();
        overrideToSave.setTenant(tenant);

        // Sanitize overrides to respect ALWAYS_ENABLED mutability rules
        Map<String, OverrideAction> cleanPages = new HashMap<>();
        if (requestDto.getPageOverrides() != null) {
            for (Map.Entry<String, OverrideAction> entry : requestDto.getPageOverrides().entrySet()) {
                if (!EntitlementCatalog.isAlwaysEnabled(entry.getKey())) {
                    cleanPages.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : OverrideAction.INHERIT);
                }
            }
        }

        Map<String, OverrideAction> cleanSettings = new HashMap<>();
        if (requestDto.getSettingOverrides() != null) {
            for (Map.Entry<String, OverrideAction> entry : requestDto.getSettingOverrides().entrySet()) {
                if (!EntitlementCatalog.isAlwaysEnabled(entry.getKey())) {
                    cleanSettings.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : OverrideAction.INHERIT);
                }
            }
        }

        Map<String, OverrideAction> cleanServices = new HashMap<>();
        if (requestDto.getServiceOverrides() != null) {
            for (Map.Entry<String, OverrideAction> entry : requestDto.getServiceOverrides().entrySet()) {
                cleanServices.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : OverrideAction.INHERIT);
            }
        }

        overrideToSave.setPageOverrides(cleanPages);
        overrideToSave.setSettingOverrides(cleanSettings);
        overrideToSave.setServiceOverrides(cleanServices);
        overrideToSave.setVersion((overrideToSave.getVersion() != null ? overrideToSave.getVersion() : 0) + 1);
        overrideToSave.setUpdatedBy(adminEmail);

        try {
            overrideRepository.saveAndFlush(overrideToSave);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent modification detected. Please refresh and try again.");
        }

        entitlementResolverService.invalidateEntitlementsCache(tenantId);

        // Record Audit Log
        platformAuditService.record("ENTITLEMENT_MATRIX_CHANGED", "SUCCESS", "Tenant", tenantId.toString(),
                "{\"updatedBy\":\"" + adminEmail + "\",\"newVersion\":" + overrideToSave.getVersion() + "}", servletRequest);

        PlatformTenantEntitlementMatrixDTO updatedMatrix = entitlementResolverService.getPlatformTenantEntitlementMatrix(tenantId);
        return ResponseEntity.ok(updatedMatrix);
    }

    @PostMapping("/tenants/{tenantId}/entitlements/apply-preset")
    @Transactional
    public ResponseEntity<?> applyPreset(
            @PathVariable UUID tenantId,
            @RequestParam String presetId,
            @AuthenticationPrincipal String email,
            HttpServletRequest servletRequest) {

        String adminEmail = verifyPlatformAdminAccess(email);
        PlatformEntitlementPresetService.PresetDefinition preset = presetService.getPresetById(presetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid preset ID: " + presetId));

        UpdateTenantEntitlementsMatrixRequestDto requestDto = UpdateTenantEntitlementsMatrixRequestDto.builder()
                .pageOverrides(preset.pageOverrides())
                .settingOverrides(preset.settingOverrides())
                .serviceOverrides(preset.serviceOverrides())
                .reason("Applied Preset: " + preset.name())
                .build();

        return updateEntitlementsMatrix(tenantId, requestDto, email, servletRequest);
    }

    @GetMapping("/tenants/{tenantId}/entitlements")
    public ResponseEntity<EffectiveEntitlementsDTO> getEntitlements(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "false") boolean trace,
            @AuthenticationPrincipal String email) {
        verifyPlatformAdminAccess(email);
        EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(tenantId, trace);
        return ResponseEntity.ok(entitlements);
    }

    @PutMapping("/tenants/{tenantId}/overrides")
    @Transactional
    public ResponseEntity<?> updateTenantOverrides(
            @PathVariable UUID tenantId,
            @RequestBody UpdateTenantOverridesRequestDto requestDto,
            @AuthenticationPrincipal String email,
            HttpServletRequest servletRequest) {

        String adminEmail = verifyPlatformAdminAccess(email);
        Tenant tenant = resolveTenant(tenantId);

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

        // Priority overrides
        if (requestDto.getMaxAllowedPriority() != null) {
            Map<String, Object> priMap = new HashMap<>();
            priMap.put("maxPriority", requestDto.getMaxAllowedPriority().name());
            overrideToSave.setPriorityOverrides(serializeMap(priMap));
        }

        // Pricing overrides
        Map<String, Object> priceMap = new HashMap<>();
        if (requestDto.getCustomMonthlyInr() != null) priceMap.put("monthlyInr", requestDto.getCustomMonthlyInr());
        if (requestDto.getCustomYearlyInr() != null) priceMap.put("yearlyInr", requestDto.getCustomYearlyInr());
        if (requestDto.getCustomMonthlyUsd() != null) priceMap.put("monthlyUsd", requestDto.getCustomMonthlyUsd());
        if (requestDto.getCustomYearlyUsd() != null) priceMap.put("yearlyUsd", requestDto.getCustomYearlyUsd());
        overrideToSave.setPricingOverrides(serializeMap(priceMap));

        if (requestDto.getEffectiveFrom() != null) overrideToSave.setEffectiveFrom(requestDto.getEffectiveFrom());
        if (requestDto.getEffectiveUntil() != null) overrideToSave.setEffectiveUntil(requestDto.getEffectiveUntil());

        overrideToSave.setVersion((overrideToSave.getVersion() != null ? overrideToSave.getVersion() : 0) + 1);
        overrideToSave.setUpdatedBy(adminEmail);
        if (overrideToSave.getCreatedBy() == null) overrideToSave.setCreatedBy(adminEmail);

        try {
            overrideRepository.saveAndFlush(overrideToSave);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("⚠️ Optimistic lock conflict while saving override for tenantId={}: {}", tenantId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent modification detected. Please refresh and try again.");
        }

        entitlementResolverService.invalidateEntitlementsCache(tenantId);

        String newValueJson = serializeOverride(overrideToSave);
        TenantSubscriptionOverrideAudit audit = TenantSubscriptionOverrideAudit.builder()
                .action(TenantSubscriptionOverrideAudit.OverrideAuditAction.UPDATE_OVERRIDE)
                .changedBy(adminEmail)
                .oldValueJson(oldValueJson)
                .newValueJson(newValueJson)
                .reason(requestDto.getReason())
                .ipAddress(servletRequest.getRemoteAddr())
                .build();
        audit.setTenant(tenant);
        auditRepository.save(audit);

        EffectiveEntitlementsDTO effective = entitlementResolverService.getEffectiveEntitlements(tenantId, true);
        return ResponseEntity.ok(effective);
    }

    private String serializeOverride(TenantSubscriptionOverride o) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("featureOverrides", o.getFeatureOverrides());
            map.put("quotaOverrides", o.getQuotaOverrides());
            map.put("priorityOverrides", o.getPriorityOverrides());
            map.put("pricingOverrides", o.getPricingOverrides());
            map.put("pageOverrides", o.getPageOverrides());
            map.put("settingOverrides", o.getSettingOverrides());
            map.put("serviceOverrides", o.getServiceOverrides());
            map.put("version", o.getVersion());
            return objectMapper.writeValueAsString(map);
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
