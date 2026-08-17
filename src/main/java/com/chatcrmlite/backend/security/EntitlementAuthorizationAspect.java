package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.entitlements.EntitlementType;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.services.tenant.EntitlementResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.UUID;

@Slf4j
@Aspect
@Component
@Order(10)
@RequiredArgsConstructor
public class EntitlementAuthorizationAspect {

    private final EntitlementResolverService entitlementResolverService;
    private final TenantRepository tenantRepository;

    @Before("@annotation(com.chatcrmlite.backend.security.RequiresEntitlement) || " +
            "@annotation(com.chatcrmlite.backend.security.RequiresPage) || " +
            "@annotation(com.chatcrmlite.backend.security.RequiresSetting) || " +
            "@annotation(com.chatcrmlite.backend.security.RequiresService)")
    public void authorizeEntitlement(JoinPoint joinPoint) {
        // Platform admin mode skips tenant entitlement checks
        if (TenantContext.isAdminMode()) {
            return;
        }

        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            log.warn("⚠️ Entitlement check triggered but TenantContext has no tenantId for execution: {}", joinPoint.getSignature());
            throw new EntitlementDeniedException("AUTHENTICATION_REQUIRED", "TENANT_CONTEXT", "Tenant identity context is required.");
        }

        // Verify Tenant Lifecycle Status (Active / Suspended)
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant != null && tenant.getLifecycleStatus() != null) {
            if (tenant.getLifecycleStatus() == Tenant.LifecycleStatus.SUSPENDED ||
                tenant.getLifecycleStatus() == Tenant.LifecycleStatus.LOCKED ||
                tenant.getLifecycleStatus() == Tenant.LifecycleStatus.ARCHIVED ||
                tenant.getLifecycleStatus() == Tenant.LifecycleStatus.DELETED) {
                throw new EntitlementDeniedException("TENANT_INACTIVE", "LIFECYCLE_STATUS",
                        "Tenant account is currently " + tenant.getLifecycleStatus() + ". Contact platform support.");
            }
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 1. Shorthand @RequiresPage
        RequiresPage requiresPage = method.getAnnotation(RequiresPage.class);
        if (requiresPage != null) {
            String pageKey = requiresPage.value();
            if (!entitlementResolverService.hasPageAccess(tenantId, pageKey)) {
                log.warn("🚫 Access denied for tenantId={} on required page={}", tenantId, pageKey);
                throw new EntitlementDeniedException("FEATURE_NOT_ENTITLED", pageKey,
                        "The page feature [" + pageKey + "] is not enabled for your organization.");
            }
            return;
        }

        // 2. Shorthand @RequiresSetting
        RequiresSetting requiresSetting = method.getAnnotation(RequiresSetting.class);
        if (requiresSetting != null) {
            String settingKey = requiresSetting.value();
            if (!entitlementResolverService.hasSettingAccess(tenantId, settingKey)) {
                log.warn("🚫 Access denied for tenantId={} on required setting={}", tenantId, settingKey);
                throw new EntitlementDeniedException("FEATURE_NOT_ENTITLED", settingKey,
                        "The settings panel [" + settingKey + "] is not enabled for your organization.");
            }
            return;
        }

        // 3. Shorthand @RequiresService
        RequiresService requiresService = method.getAnnotation(RequiresService.class);
        if (requiresService != null) {
            String serviceKey = requiresService.value();
            if (!entitlementResolverService.hasServiceAccess(tenantId, serviceKey)) {
                log.warn("🚫 Access denied for tenantId={} on required service={}", tenantId, serviceKey);
                throw new EntitlementDeniedException("SERVICE_DISABLED", serviceKey,
                        "The backend service [" + serviceKey + "] is disabled or not included in your organization subscription.");
            }
            return;
        }

        // 4. Generic typed @RequiresEntitlement
        RequiresEntitlement requiresEntitlement = method.getAnnotation(RequiresEntitlement.class);
        if (requiresEntitlement != null) {
            EntitlementType type = requiresEntitlement.type();
            String key = requiresEntitlement.key();

            boolean allowed = switch (type) {
                case PAGE -> entitlementResolverService.hasPageAccess(tenantId, key);
                case SETTING -> entitlementResolverService.hasSettingAccess(tenantId, key);
                case SERVICE -> entitlementResolverService.hasServiceAccess(tenantId, key);
            };

            if (!allowed) {
                String code = (type == EntitlementType.SERVICE) ? "SERVICE_DISABLED" : "FEATURE_NOT_ENTITLED";
                log.warn("🚫 Access denied for tenantId={} on required entitlement type={} key={}", tenantId, type, key);
                throw new EntitlementDeniedException(code, key,
                        "The feature [" + key + "] is not available for your organization.");
            }
        }
    }
}
