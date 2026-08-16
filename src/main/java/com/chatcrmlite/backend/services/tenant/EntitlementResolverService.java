package com.chatcrmlite.backend.services.tenant;

import com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO;
import com.chatcrmlite.backend.dtos.entitlements.PlatformTenantEntitlementMatrixDTO;
import com.chatcrmlite.backend.dtos.entitlements.TenantEffectiveEntitlementsDTO;
import com.chatcrmlite.backend.models.SubscriptionPlan;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantSubscription;
import com.chatcrmlite.backend.models.TenantSubscriptionOverride;
import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.entitlements.*;
import com.chatcrmlite.backend.repositories.SubscriptionPlanRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.TenantSubscriptionOverrideRepository;
import com.chatcrmlite.backend.repositories.TenantSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementResolverService {

    private static final String REDIS_CURRENT_POINTER_PREFIX = "tenant:entitlements:current:";
    private static final String REDIS_DATA_PREFIX = "tenant:entitlements:";
    private static final String REDIS_TENANT_DTO_PREFIX = "tenant:entitlements:dto:";

    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final TenantSubscriptionOverrideRepository overrideRepository;
    private final TenantRepository tenantRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public EffectiveEntitlementsDTO getEffectiveEntitlements(UUID tenantId) {
        return getEffectiveEntitlements(tenantId, false);
    }

    public EffectiveEntitlementsDTO getEffectiveEntitlements(UUID tenantId, boolean includeTrace) {
        if (tenantId == null) {
            return buildDefaultEntitlements("FREE", subscriptionPlanRepository.findById("FREE").orElse(null), null, includeTrace);
        }

        String pointerKey = REDIS_CURRENT_POINTER_PREFIX + tenantId;
        String currentVersionTag = stringRedisTemplate.opsForValue().get(pointerKey);

        if (currentVersionTag != null && !includeTrace) {
            String dataKey = REDIS_DATA_PREFIX + tenantId + ":" + currentVersionTag;
            String cachedJson = stringRedisTemplate.opsForValue().get(dataKey);
            if (cachedJson != null) {
                try {
                    return objectMapper.readValue(cachedJson, EffectiveEntitlementsDTO.class);
                } catch (Exception e) {
                    log.warn("⚠️ Failed deserializing cached entitlements for tenantId={}: {}", tenantId, e.getMessage());
                }
            }
        }

        EffectiveEntitlementsDTO resolved = resolveFromDatabase(tenantId, includeTrace);

        try {
            int ver = resolved.getEntitlementVersion() != null ? resolved.getEntitlementVersion() : 1;
            String versionTag = "v" + ver;
            String dataKey = REDIS_DATA_PREFIX + tenantId + ":" + versionTag;

            String jsonPayload = objectMapper.writeValueAsString(resolved);
            stringRedisTemplate.opsForValue().set(dataKey, jsonPayload, 1, TimeUnit.HOURS);
            stringRedisTemplate.opsForValue().set(pointerKey, versionTag, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("⚠️ Failed caching resolved entitlements in Redis for tenantId={}: {}", tenantId, e.getMessage());
        }

        return resolved;
    }

    @Transactional(readOnly = true)
    public TenantEffectiveEntitlementsDTO getTenantEffectiveEntitlements(UUID tenantId) {
        if (tenantId == null) {
            return buildTenantEffectiveEntitlementsFallback("FREE", null, 1);
        }

        String pointerKey = REDIS_CURRENT_POINTER_PREFIX + tenantId;
        String currentVersionTag = stringRedisTemplate.opsForValue().get(pointerKey);

        if (currentVersionTag != null) {
            String dataKey = REDIS_TENANT_DTO_PREFIX + tenantId + ":" + currentVersionTag;
            String cachedJson = stringRedisTemplate.opsForValue().get(dataKey);
            if (cachedJson != null) {
                try {
                    return objectMapper.readValue(cachedJson, TenantEffectiveEntitlementsDTO.class);
                } catch (Exception e) {
                    log.warn("⚠️ Failed deserializing cached tenant DTO for tenantId={}: {}", tenantId, e.getMessage());
                }
            }
        }

        TenantEffectiveEntitlementsDTO resolved = resolveTenantEffectiveEntitlementsFromDb(tenantId);

        try {
            int ver = resolved.getEntitlementVersion() != null ? resolved.getEntitlementVersion() : 1;
            String versionTag = "v" + ver;
            String dataKey = REDIS_TENANT_DTO_PREFIX + tenantId + ":" + versionTag;

            String jsonPayload = objectMapper.writeValueAsString(resolved);
            stringRedisTemplate.opsForValue().set(dataKey, jsonPayload, 1, TimeUnit.HOURS);
            stringRedisTemplate.opsForValue().set(pointerKey, versionTag, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("⚠️ Failed caching tenant entitlements DTO for tenantId={}: {}", tenantId, e.getMessage());
        }

        return resolved;
    }

    @Transactional(readOnly = true)
    public PlatformTenantEntitlementMatrixDTO getPlatformTenantEntitlementMatrix(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        TenantSubscription sub = tenantSubscriptionRepository.findByTenantId(tenantId).orElse(null);
        SubscriptionPlan basePlan = (sub != null && sub.getPlan() != null) ? sub.getPlan() : subscriptionPlanRepository.findById("FREE").orElse(null);
        String planId = basePlan != null ? basePlan.getId() : "FREE";
        String planName = basePlan != null ? basePlan.getName() : "Free Plan";

        TenantSubscriptionOverride override = overrideRepository.findByTenantId(tenantId).orElse(null);

        Map<String, OverrideAction> pageOverrides = override != null && override.getPageOverrides() != null ? new HashMap<>(override.getPageOverrides()) : new HashMap<>();
        Map<String, OverrideAction> settingOverrides = override != null && override.getSettingOverrides() != null ? new HashMap<>(override.getSettingOverrides()) : new HashMap<>();
        Map<String, OverrideAction> serviceOverrides = override != null && override.getServiceOverrides() != null ? new HashMap<>(override.getServiceOverrides()) : new HashMap<>();

        Map<String, Boolean> rawEffective = evaluateEffectiveMatrix(planId, basePlan, pageOverrides, settingOverrides, serviceOverrides);
        Map<String, Boolean> recursiveEffective = applyRecursiveDependencies(rawEffective);

        Map<String, Boolean> effectivePages = new HashMap<>();
        Map<String, Boolean> effectiveSettings = new HashMap<>();
        Map<String, Boolean> effectiveServices = new HashMap<>();

        for (EntitlementDefinition def : EntitlementCatalog.getAll()) {
            boolean isAllowed = recursiveEffective.getOrDefault(def.key(), true);
            if (def.type() == EntitlementType.PAGE) {
                effectivePages.put(def.key(), isAllowed);
            } else if (def.type() == EntitlementType.SETTING) {
                effectiveSettings.put(def.key(), isAllowed);
            } else if (def.type() == EntitlementType.SERVICE) {
                effectiveServices.put(def.key(), isAllowed);
            }
        }

        return PlatformTenantEntitlementMatrixDTO.builder()
                .tenantId(tenantId)
                .businessName(tenant != null ? tenant.getBusinessName() : "Unknown Tenant")
                .planId(planId)
                .planName(planName)
                .entitlementVersion(override != null ? override.getVersion() : 1)
                .pageOverrides(pageOverrides)
                .settingOverrides(settingOverrides)
                .serviceOverrides(serviceOverrides)
                .effectivePages(effectivePages)
                .effectiveSettings(effectiveSettings)
                .effectiveServices(effectiveServices)
                .catalog(EntitlementCatalog.getAll())
                .build();
    }

    public boolean hasPageAccess(UUID tenantId, String pageKey) {
        if (pageKey == null || pageKey.isBlank()) return true;
        if (EntitlementCatalog.isAlwaysEnabled(pageKey)) return true;
        TenantEffectiveEntitlementsDTO dto = getTenantEffectiveEntitlements(tenantId);
        return dto != null && Boolean.TRUE.equals(dto.getPages().get(pageKey));
    }

    public boolean hasSettingAccess(UUID tenantId, String settingKey) {
        if (settingKey == null || settingKey.isBlank()) return true;
        if (EntitlementCatalog.isAlwaysEnabled(settingKey)) return true;
        TenantEffectiveEntitlementsDTO dto = getTenantEffectiveEntitlements(tenantId);
        return dto != null && Boolean.TRUE.equals(dto.getSettings().get(settingKey));
    }

    public boolean hasServiceAccess(UUID tenantId, String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) return true;
        TenantEffectiveEntitlementsDTO dto = getTenantEffectiveEntitlements(tenantId);
        return dto != null && Boolean.TRUE.equals(dto.getServices().get(serviceKey));
    }

    public void invalidateEntitlementsCache(UUID tenantId) {
        if (tenantId == null) return;
        try {
            String pointerKey = REDIS_CURRENT_POINTER_PREFIX + tenantId;
            String currentVersionTag = stringRedisTemplate.opsForValue().get(pointerKey);
            if (currentVersionTag != null) {
                String dataKey = REDIS_DATA_PREFIX + tenantId + ":" + currentVersionTag;
                String dtoKey = REDIS_TENANT_DTO_PREFIX + tenantId + ":" + currentVersionTag;
                stringRedisTemplate.delete(dataKey);
                stringRedisTemplate.delete(dtoKey);
            }
            stringRedisTemplate.delete(pointerKey);
            log.info("🧹 Invalidated entitlements Redis cache pointer for tenantId={}", tenantId);
        } catch (Exception e) {
            log.warn("⚠️ Failed invalidating Redis entitlements cache for tenantId={}: {}", tenantId, e.getMessage());
        }
    }

    private TenantEffectiveEntitlementsDTO resolveTenantEffectiveEntitlementsFromDb(UUID tenantId) {
        TenantSubscription sub = tenantSubscriptionRepository.findByTenantId(tenantId).orElse(null);
        SubscriptionPlan basePlan = (sub != null && sub.getPlan() != null) ? sub.getPlan() : subscriptionPlanRepository.findById("FREE").orElse(null);
        String planId = basePlan != null ? basePlan.getId() : "FREE";
        String planName = basePlan != null ? basePlan.getName() : "Free Plan";

        TenantSubscriptionOverride override = overrideRepository.findByTenantId(tenantId).orElse(null);

        Map<String, OverrideAction> pageOverrides = override != null && override.getPageOverrides() != null ? override.getPageOverrides() : Collections.emptyMap();
        Map<String, OverrideAction> settingOverrides = override != null && override.getSettingOverrides() != null ? override.getSettingOverrides() : Collections.emptyMap();
        Map<String, OverrideAction> serviceOverrides = override != null && override.getServiceOverrides() != null ? override.getServiceOverrides() : Collections.emptyMap();

        Map<String, Boolean> rawEffective = evaluateEffectiveMatrix(planId, basePlan, pageOverrides, settingOverrides, serviceOverrides);
        Map<String, Boolean> recursiveEffective = applyRecursiveDependencies(rawEffective);

        Map<String, Boolean> pages = new HashMap<>();
        Map<String, Boolean> settings = new HashMap<>();
        Map<String, Boolean> services = new HashMap<>();

        for (EntitlementDefinition def : EntitlementCatalog.getAll()) {
            boolean isAllowed = recursiveEffective.getOrDefault(def.key(), true);
            if (def.type() == EntitlementType.PAGE) {
                pages.put(def.key(), isAllowed);
            } else if (def.type() == EntitlementType.SETTING) {
                settings.put(def.key(), isAllowed);
            } else if (def.type() == EntitlementType.SERVICE) {
                services.put(def.key(), isAllowed);
            }
        }

        EffectiveEntitlementsDTO legacy = buildMergedEntitlements(planId, basePlan, override, false);

        return TenantEffectiveEntitlementsDTO.builder()
                .tenantId(tenantId.toString())
                .planId(planId)
                .planName(planName)
                .entitlementVersion(override != null ? override.getVersion() : 1)
                .pages(pages)
                .settings(settings)
                .services(services)
                .limits(legacy.getLimits())
                .build();
    }

    private TenantEffectiveEntitlementsDTO buildTenantEffectiveEntitlementsFallback(String planId, SubscriptionPlan basePlan, int version) {
        Map<String, Boolean> rawEffective = evaluateEffectiveMatrix(planId, basePlan, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        Map<String, Boolean> recursiveEffective = applyRecursiveDependencies(rawEffective);

        Map<String, Boolean> pages = new HashMap<>();
        Map<String, Boolean> settings = new HashMap<>();
        Map<String, Boolean> services = new HashMap<>();

        for (EntitlementDefinition def : EntitlementCatalog.getAll()) {
            boolean isAllowed = recursiveEffective.getOrDefault(def.key(), true);
            if (def.type() == EntitlementType.PAGE) pages.put(def.key(), isAllowed);
            else if (def.type() == EntitlementType.SETTING) settings.put(def.key(), isAllowed);
            else if (def.type() == EntitlementType.SERVICE) services.put(def.key(), isAllowed);
        }

        return TenantEffectiveEntitlementsDTO.builder()
                .tenantId(null)
                .planId(planId)
                .planName(basePlan != null ? basePlan.getName() : "Free Plan")
                .entitlementVersion(version)
                .pages(pages)
                .settings(settings)
                .services(services)
                .limits(EffectiveEntitlementsDTO.LimitsDTO.builder()
                        .employeeLimit(1)
                        .primaryResourceLimit(100)
                        .secondaryResourceLimit(15)
                        .ticketLimit(10)
                        .emailLimit(500)
                        .maxRecipientsPerWhatsappCampaign(0)
                        .monthlyWhatsappMessageQuota(0)
                        .build())
                .build();
    }

    private Map<String, Boolean> evaluateEffectiveMatrix(
            String planId,
            SubscriptionPlan basePlan,
            Map<String, OverrideAction> pageOverrides,
            Map<String, OverrideAction> settingOverrides,
            Map<String, OverrideAction> serviceOverrides) {

        Map<String, Boolean> result = new HashMap<>();

        for (EntitlementDefinition def : EntitlementCatalog.getAll()) {
            String key = def.key();

            // 1. Check Always Enabled rules
            if (def.mutability() == EntitlementMutability.ALWAYS_ENABLED) {
                result.put(key, true);
                continue;
            }

            // 2. Base Plan Default
            boolean baseDefault = getBasePlanDefault(planId, basePlan, def);

            // 3. Override Check
            OverrideAction action = OverrideAction.INHERIT;
            if (def.type() == EntitlementType.PAGE && pageOverrides.containsKey(key)) {
                action = pageOverrides.get(key);
            } else if (def.type() == EntitlementType.SETTING && settingOverrides.containsKey(key)) {
                action = settingOverrides.get(key);
            } else if (def.type() == EntitlementType.SERVICE && serviceOverrides.containsKey(key)) {
                action = serviceOverrides.get(key);
            }

            if (action == OverrideAction.ALLOW) {
                result.put(key, true);
            } else if (action == OverrideAction.DENY) {
                result.put(key, false);
            } else {
                result.put(key, baseDefault);
            }
        }

        return result;
    }

    private boolean getBasePlanDefault(String planId, SubscriptionPlan basePlan, EntitlementDefinition def) {
        String key = def.key();
        boolean isFree = "FREE".equalsIgnoreCase(planId) || planId == null;
        boolean isProOrHigher = !isFree;

        if (key.equals("SERVICE_WHATSAPP_API") || key.equals("PAGE_BROADCASTS") || key.equals("PAGE_META_CONFIG")) {
            return basePlan != null ? basePlan.isHasWhatsapp() : isProOrHigher;
        }
        if (key.equals("SERVICE_AI_RAG_LLM") || key.equals("PAGE_KNOWLEDGE_BASE") || key.equals("SETTINGS_SUPPORT_CATEGORIES")) {
            return basePlan != null ? basePlan.isHasRagLlm() : "ENTERPRISE".equalsIgnoreCase(planId);
        }
        if (key.equals("SERVICE_EMAIL_DISPATCH") || key.equals("PAGE_EMAILS") || key.equals("SETTINGS_EMAIL_PROVIDERS")) {
            return true;
        }
        if (key.equals("SERVICE_FLOW_AUTOMATION") || key.equals("SETTINGS_WHATSAPP_FLOWS") || key.equals("SETTINGS_FLOW_CTA")) {
            return isProOrHigher;
        }
        if (key.equals("PAGE_PROPERTIES") || key.equals("SETTINGS_PRODUCTS") || key.equals("SETTINGS_MENU_BUILDER")) {
            return true;
        }

        return true;
    }

    /**
     * Traverses dependency graph recursively. If any dependency is false, the dependent feature is forced false.
     */
    private Map<String, Boolean> applyRecursiveDependencies(Map<String, Boolean> map) {
        Map<String, Boolean> resolved = new HashMap<>(map);
        boolean changed = true;
        int maxPasses = 10;
        int pass = 0;

        while (changed && pass < maxPasses) {
            changed = false;
            pass++;

            for (EntitlementDefinition def : EntitlementCatalog.getAll()) {
                String key = def.key();
                if (!resolved.getOrDefault(key, false)) continue; // Already disabled

                if (def.dependencies() != null && !def.dependencies().isEmpty()) {
                    for (String depKey : def.dependencies()) {
                        boolean depAllowed = resolved.getOrDefault(depKey, false);
                        if (!depAllowed) {
                            resolved.put(key, false);
                            changed = true;
                            log.debug("🔒 Dependency rule triggered: {} disabled because required dependency {} is disabled.", key, depKey);
                            break;
                        }
                    }
                }
            }
        }

        return resolved;
    }

    private EffectiveEntitlementsDTO resolveFromDatabase(UUID tenantId, boolean includeTrace) {
        TenantSubscription sub = tenantSubscriptionRepository.findByTenantId(tenantId).orElse(null);
        SubscriptionPlan basePlan = (sub != null && sub.getPlan() != null) ? sub.getPlan() : subscriptionPlanRepository.findById("FREE").orElse(null);
        String planId = basePlan != null ? basePlan.getId() : "FREE";

        TenantSubscriptionOverride override = overrideRepository.findByTenantId(tenantId).orElse(null);

        boolean isOverrideActive = false;
        if (override != null) {
            LocalDateTime now = LocalDateTime.now();
            boolean afterFrom = (override.getEffectiveFrom() == null || !now.isBefore(override.getEffectiveFrom()));
            boolean beforeUntil = (override.getEffectiveUntil() == null || !now.isAfter(override.getEffectiveUntil()));
            isOverrideActive = afterFrom && beforeUntil;
        }

        if (!isOverrideActive) {
            return buildDefaultEntitlements(planId, basePlan, override != null ? override.getVersion() : 1, includeTrace);
        }

        return buildMergedEntitlements(planId, basePlan, override, includeTrace);
    }

    private EffectiveEntitlementsDTO buildDefaultEntitlements(String planId, SubscriptionPlan plan, Integer version, boolean includeTrace) {
        boolean hasWa = plan != null && plan.isHasWhatsapp();
        boolean hasWaCamp = plan != null && plan.isHasWhatsappCampaign();
        boolean hasWidget = plan != null && plan.isHasCustomWidget();
        boolean hasRag = plan != null && plan.isHasRagLlm();

        int empLimit = plan != null ? plan.getEmployeeLimit() : 1;
        int priLimit = plan != null ? plan.getPrimaryResourceLimit() : 100;
        int secLimit = plan != null ? plan.getSecondaryResourceLimit() : 15;
        int tktLimit = plan != null ? plan.getTicketLimit() : 10;
        int emlLimit = plan != null ? plan.getEmailLimit() : 500;
        int maxRecip = plan != null ? plan.getWhatsappCampaignLimit() : (hasWa ? 2500 : 0);

        WhatsAppCampaign.Priority maxPri = resolveBaseMaxPriority(planId);
        List<WhatsAppCampaign.Priority> allowedPriorities = deriveAllowedPriorities(maxPri);

        Map<String, EffectiveEntitlementsDTO.PropertyTrace> trace = includeTrace ? new HashMap<>() : null;
        if (includeTrace) {
            trace.put("hasWhatsapp", new EffectiveEntitlementsDTO.PropertyTrace(hasWa, "BASE_PLAN"));
            trace.put("hasWhatsappCampaign", new EffectiveEntitlementsDTO.PropertyTrace(hasWaCamp, "BASE_PLAN"));
            trace.put("hasCustomWidget", new EffectiveEntitlementsDTO.PropertyTrace(hasWidget, "BASE_PLAN"));
            trace.put("hasRagLlm", new EffectiveEntitlementsDTO.PropertyTrace(hasRag, "BASE_PLAN"));
            trace.put("employeeLimit", new EffectiveEntitlementsDTO.PropertyTrace(empLimit, "BASE_PLAN"));
            trace.put("maxRecipientsPerWhatsappCampaign", new EffectiveEntitlementsDTO.PropertyTrace(maxRecip, "BASE_PLAN"));
            trace.put("maxAllowedPriority", new EffectiveEntitlementsDTO.PropertyTrace(maxPri.name(), "BASE_PLAN"));
        }

        return EffectiveEntitlementsDTO.builder()
                .basePlanId(planId)
                .basePlanName(plan != null ? plan.getName() : "Free Starter Pack")
                .isCustomized(false)
                .entitlementVersion(version != null ? version : 1)
                .features(EffectiveEntitlementsDTO.FeaturesDTO.builder()
                        .hasWhatsapp(hasWa)
                        .hasWhatsappCampaign(hasWaCamp)
                        .hasCustomWidget(hasWidget)
                        .hasRagLlm(hasRag)
                        .hasEmailCampaign(true)
                        .build())
                .limits(EffectiveEntitlementsDTO.LimitsDTO.builder()
                        .employeeLimit(empLimit)
                        .primaryResourceLimit(priLimit)
                        .secondaryResourceLimit(secLimit)
                        .ticketLimit(tktLimit)
                        .emailLimit(emlLimit)
                        .maxRecipientsPerWhatsappCampaign(maxRecip)
                        .monthlyWhatsappMessageQuota(maxRecip)
                        .build())
                .pricing(EffectiveEntitlementsDTO.PricingDTO.builder()
                        .monthlyInr(plan != null ? plan.getPriceMonthlyInr() : BigDecimal.ZERO)
                        .yearlyInr(plan != null ? plan.getPriceYearlyInr() : BigDecimal.ZERO)
                        .monthlyUsd(plan != null ? plan.getPriceMonthlyUsd() : BigDecimal.ZERO)
                        .yearlyUsd(plan != null ? plan.getPriceYearlyUsd() : BigDecimal.ZERO)
                        .build())
                .maxAllowedPriority(maxPri)
                .allowedPriorities(allowedPriorities)
                .trace(trace)
                .build();
    }

    private EffectiveEntitlementsDTO buildMergedEntitlements(String planId, SubscriptionPlan basePlan, TenantSubscriptionOverride override, boolean includeTrace) {
        if (override == null) {
            return buildDefaultEntitlements(planId, basePlan, 1, includeTrace);
        }

        Map<String, Object> featMap = parseJsonMap(override.getFeatureOverrides());
        Map<String, Object> quotaMap = parseJsonMap(override.getQuotaOverrides());
        Map<String, Object> priMap = parseJsonMap(override.getPriorityOverrides());
        Map<String, Object> priceMap = parseJsonMap(override.getPricingOverrides());

        Map<String, EffectiveEntitlementsDTO.PropertyTrace> trace = includeTrace ? new HashMap<>() : null;

        boolean hasWa = resolveBoolean(featMap, "hasWhatsapp", basePlan != null && basePlan.isHasWhatsapp(), trace, "hasWhatsapp");
        boolean hasWaCamp = resolveBoolean(featMap, "hasWhatsappCampaign", basePlan != null && basePlan.isHasWhatsappCampaign(), trace, "hasWhatsappCampaign");
        boolean hasWidget = resolveBoolean(featMap, "hasCustomWidget", basePlan != null && basePlan.isHasCustomWidget(), trace, "hasCustomWidget");
        boolean hasRag = resolveBoolean(featMap, "hasRagLlm", basePlan != null && basePlan.isHasRagLlm(), trace, "hasRagLlm");
        boolean hasEmailCamp = resolveBoolean(featMap, "hasEmailCampaign", true, trace, "hasEmailCampaign");

        int empLimit = resolveInt(quotaMap, "employeeLimit", basePlan != null ? basePlan.getEmployeeLimit() : 1, trace, "employeeLimit");
        int priLimit = resolveInt(quotaMap, "primaryResourceLimit", basePlan != null ? basePlan.getPrimaryResourceLimit() : 100, trace, "primaryResourceLimit");
        int secLimit = resolveInt(quotaMap, "secondaryResourceLimit", basePlan != null ? basePlan.getSecondaryResourceLimit() : 15, trace, "secondaryResourceLimit");
        int tktLimit = resolveInt(quotaMap, "ticketLimit", basePlan != null ? basePlan.getTicketLimit() : 10, trace, "ticketLimit");
        int emlLimit = resolveInt(quotaMap, "emailLimit", basePlan != null ? basePlan.getEmailLimit() : 500, trace, "emailLimit");
        int maxRecip = resolveInt(quotaMap, "maxRecipientsPerWhatsappCampaign", basePlan != null ? basePlan.getWhatsappCampaignLimit() : (hasWa ? 2500 : 0), trace, "maxRecipientsPerWhatsappCampaign");
        int monthlyWaQuota = resolveInt(quotaMap, "monthlyWhatsappMessageQuota", maxRecip, trace, "monthlyWhatsappMessageQuota");

        WhatsAppCampaign.Priority baseMaxPri = resolveBaseMaxPriority(planId);
        WhatsAppCampaign.Priority maxPri = baseMaxPri;
        if (priMap != null && priMap.containsKey("maxPriority")) {
            try {
                maxPri = WhatsAppCampaign.Priority.valueOf(priMap.get("maxPriority").toString().toUpperCase());
                if (includeTrace) trace.put("maxAllowedPriority", new EffectiveEntitlementsDTO.PropertyTrace(maxPri.name(), "TENANT_OVERRIDE"));
            } catch (Exception e) {
                if (includeTrace) trace.put("maxAllowedPriority", new EffectiveEntitlementsDTO.PropertyTrace(baseMaxPri.name(), "BASE_PLAN"));
            }
        } else {
            if (includeTrace) trace.put("maxAllowedPriority", new EffectiveEntitlementsDTO.PropertyTrace(baseMaxPri.name(), "BASE_PLAN"));
        }

        List<WhatsAppCampaign.Priority> allowedPriorities = deriveAllowedPriorities(maxPri);

        BigDecimal mInr = resolveBigDecimal(priceMap, "monthlyInr", basePlan != null ? basePlan.getPriceMonthlyInr() : BigDecimal.ZERO);
        BigDecimal yInr = resolveBigDecimal(priceMap, "yearlyInr", basePlan != null ? basePlan.getPriceYearlyInr() : BigDecimal.ZERO);
        BigDecimal mUsd = resolveBigDecimal(priceMap, "monthlyUsd", basePlan != null ? basePlan.getPriceMonthlyUsd() : BigDecimal.ZERO);
        BigDecimal yUsd = resolveBigDecimal(priceMap, "yearlyUsd", basePlan != null ? basePlan.getPriceYearlyUsd() : BigDecimal.ZERO);

        return EffectiveEntitlementsDTO.builder()
                .basePlanId(planId)
                .basePlanName(basePlan != null ? basePlan.getName() : "Custom Tenant Plan")
                .isCustomized(true)
                .entitlementVersion(override.getVersion())
                .features(EffectiveEntitlementsDTO.FeaturesDTO.builder()
                        .hasWhatsapp(hasWa)
                        .hasWhatsappCampaign(hasWaCamp)
                        .hasCustomWidget(hasWidget)
                        .hasRagLlm(hasRag)
                        .hasEmailCampaign(hasEmailCamp)
                        .build())
                .limits(EffectiveEntitlementsDTO.LimitsDTO.builder()
                        .employeeLimit(empLimit)
                        .primaryResourceLimit(priLimit)
                        .secondaryResourceLimit(secLimit)
                        .ticketLimit(tktLimit)
                        .emailLimit(emlLimit)
                        .maxRecipientsPerWhatsappCampaign(maxRecip)
                        .monthlyWhatsappMessageQuota(monthlyWaQuota)
                        .build())
                .pricing(EffectiveEntitlementsDTO.PricingDTO.builder()
                        .monthlyInr(mInr)
                        .yearlyInr(yInr)
                        .monthlyUsd(mUsd)
                        .yearlyUsd(yUsd)
                        .build())
                .maxAllowedPriority(maxPri)
                .allowedPriorities(allowedPriorities)
                .trace(trace)
                .build();
    }

    private WhatsAppCampaign.Priority resolveBaseMaxPriority(String planId) {
        if ("ENTERPRISE".equalsIgnoreCase(planId)) return WhatsAppCampaign.Priority.HIGH;
        if ("PRO".equalsIgnoreCase(planId)) return WhatsAppCampaign.Priority.MEDIUM;
        return WhatsAppCampaign.Priority.LOW;
    }

    private List<WhatsAppCampaign.Priority> deriveAllowedPriorities(WhatsAppCampaign.Priority maxPri) {
        if (maxPri == WhatsAppCampaign.Priority.HIGH) {
            return List.of(WhatsAppCampaign.Priority.LOW, WhatsAppCampaign.Priority.MEDIUM, WhatsAppCampaign.Priority.HIGH);
        }
        if (maxPri == WhatsAppCampaign.Priority.MEDIUM) {
            return List.of(WhatsAppCampaign.Priority.LOW, WhatsAppCampaign.Priority.MEDIUM);
        }
        return List.of(WhatsAppCampaign.Priority.LOW);
    }

    private boolean resolveBoolean(Map<String, Object> map, String key, boolean fallback, Map<String, EffectiveEntitlementsDTO.PropertyTrace> trace, String traceKey) {
        if (map != null && map.containsKey(key)) {
            boolean val = Boolean.parseBoolean(map.get(key).toString());
            if (trace != null) trace.put(traceKey, new EffectiveEntitlementsDTO.PropertyTrace(val, "TENANT_OVERRIDE"));
            return val;
        }
        if (trace != null) trace.put(traceKey, new EffectiveEntitlementsDTO.PropertyTrace(fallback, "BASE_PLAN"));
        return fallback;
    }

    private int resolveInt(Map<String, Object> map, String key, int fallback, Map<String, EffectiveEntitlementsDTO.PropertyTrace> trace, String traceKey) {
        if (map != null && map.containsKey(key)) {
            try {
                int val = Integer.parseInt(map.get(key).toString());
                if (trace != null) trace.put(traceKey, new EffectiveEntitlementsDTO.PropertyTrace(val, "TENANT_OVERRIDE"));
                return val;
            } catch (Exception ignored) {}
        }
        if (trace != null) trace.put(traceKey, new EffectiveEntitlementsDTO.PropertyTrace(fallback, "BASE_PLAN"));
        return fallback;
    }

    private BigDecimal resolveBigDecimal(Map<String, Object> map, String key, BigDecimal fallback) {
        if (map != null && map.containsKey(key)) {
            try {
                return new BigDecimal(map.get(key).toString());
            } catch (Exception ignored) {}
        }
        return fallback != null ? fallback : BigDecimal.ZERO;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
