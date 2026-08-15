package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.FlowConfigDTO;
import com.chatcrmlite.backend.dto.FlowStepDTO;
import com.chatcrmlite.backend.dto.flow.FlowFieldConfig;
import com.chatcrmlite.backend.dto.flow.TenantFlowConfigJson;
import com.chatcrmlite.backend.models.ConversationState;
import com.chatcrmlite.backend.models.TenantFlowConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TenantFlowConfigRepository;
import com.chatcrmlite.backend.models.SupportFormConfig;
import com.chatcrmlite.backend.repositories.SupportFormConfigRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FlowConfigService {
    private static final Logger log = LoggerFactory.getLogger(FlowConfigService.class);

    private final ObjectMapper objectMapper;
    private final TenantFlowConfigRepository tenantFlowConfigRepository;
    private final SupportFormConfigRepository supportFormConfigRepository;
    private final FlowTemplateEngine flowTemplateEngine;

    @Autowired
    public FlowConfigService(ObjectMapper objectMapper, 
                             TenantFlowConfigRepository tenantFlowConfigRepository, 
                             SupportFormConfigRepository supportFormConfigRepository,
                             FlowTemplateEngine flowTemplateEngine) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.tenantFlowConfigRepository = tenantFlowConfigRepository;
        this.supportFormConfigRepository = supportFormConfigRepository;
        this.flowTemplateEngine = flowTemplateEngine;
    }

    public FlowConfigDTO getFlowConfig(User user) {
        return getFlowConfig(user, null);
    }

    @Transactional
    public FlowConfigDTO getFlowConfig(User user, String explicitSuffix) {
        ConversationState.FlowType flowTypeEnum = resolveFlowTypeEnum(user, explicitSuffix);

        FlowConfigDTO config;

        // ── SUPPORT FLOW: DB is the primary source of truth ──────────────────
        // On first access for this tenant, auto-seed from support.json.
        // Subsequent accesses use the tenant's own DB row (which they can edit).
        if (flowTypeEnum == ConversationState.FlowType.SUPPORT && user != null) {
            config = getOrSeedSupportFlowConfig(user);
        } else if ((flowTypeEnum == ConversationState.FlowType.APPOINTMENT
                || flowTypeEnum == ConversationState.FlowType.BOOKING
                || flowTypeEnum == ConversationState.FlowType.LEAD_CAPTURE
                || flowTypeEnum == ConversationState.FlowType.ENQUIRY) && user != null) {
            // ── DB-first for all non-SUPPORT flows ───────────────────────────────
            // DB is the single source of truth. If no DB config exists yet,
            // auto-seed from master-fields.json and save to DB (first-time only).
            config = getOrSeedFlowConfig(user, flowTypeEnum);
        } else {
            // ── Fallback for unauthenticated or unknown flow types ────────────────
            config = loadFlow("master-fields");
            if (config == null) {
                log.error("[FlowConfigService] master-fields.json missing from classpath — returning empty config");
                return FlowConfigDTO.builder().flowType(flowTypeEnum.name()).build();
            }
            config.setFlowType(flowTypeEnum.name());
        }

        // Post-process to resolve dynamic option sources
        if (config != null && config.getSteps() != null) {
            for (FlowStepDTO step : config.getSteps()) {
                boolean isDynamicKey = "category".equals(step.getDataKey()) || "service".equals(step.getDataKey())
                        || "services".equals(step.getDataKey()) || "service_type".equals(step.getDataKey())
                        || "service_category".equals(step.getDataKey()) || "treatment".equals(step.getDataKey())
                        || "consultation_type".equals(step.getDataKey());
                
                if (isDynamicKey) {
                    step.setDynamicSource(true);
                    step.setUsesButtons(true);
                }
            }
        }

        return config;
    }

    /**
     * Returns the SUPPORT flow config for the given tenant from DB.
     * If no DB row exists yet, seeds one from {@code support.json} and saves it.
     * This makes the DB the single source of truth for SUPPORT steps while
     * keeping the JSON file as a fallback seed/template.
     */
    private FlowConfigDTO getOrSeedSupportFlowConfig(User user) {
        Optional<TenantFlowConfig> dbRowOpt =
                tenantFlowConfigRepository.findByTenantAndFlowType(user, ConversationState.FlowType.SUPPORT);

        if (dbRowOpt.isPresent()) {
            TenantFlowConfig row = dbRowOpt.get();
            try {
                TenantFlowConfigJson configJson = objectMapper.readValue(row.getConfigurationJson(), TenantFlowConfigJson.class);
                if (configJson != null && configJson.getFields() != null && !configJson.getFields().isEmpty()) {
                    // DB row exists and has fields — build config from tenant's stored steps
                    return buildSupportConfigFromDbRow(row);
                }
                
                // Row exists but has no fields (e.g. greeting saved first) — seed fields into the existing row
                log.info("[FlowConfigService] SUPPORT flow config exists for tenant={} but has no fields — seeding fields", user.getId());
                FlowConfigDTO jsonConfig = loadFlow("support");
                if (jsonConfig != null) {
                    List<FlowFieldConfig> fields = stepsToFieldConfigs(jsonConfig.getSteps());
                    if (configJson == null) {
                        configJson = new TenantFlowConfigJson();
                    }
                    configJson.setFields(fields);
                    row.setConfigurationJson(objectMapper.writeValueAsString(configJson));
                    tenantFlowConfigRepository.save(row);
                    return buildSupportConfigFromDbRow(row);
                }
            } catch (Exception e) {
                log.error("[FlowConfigService] Failed to check/seed fields in existing SUPPORT row for tenant={}: {}",
                        user.getId(), e.getMessage());
            }
            return buildSupportConfigFromDbRow(row);
        }

        // No DB row yet — seed from support.json and persist
        log.info("[FlowConfigService] No SUPPORT flow config in DB for tenant={} — seeding from support.json", user.getId());
        FlowConfigDTO jsonConfig = loadFlow("support");
        if (jsonConfig == null) {
            log.error("[FlowConfigService] support.json missing — returning minimal SUPPORT config for tenant={}", user.getId());
            return FlowConfigDTO.builder().flowType("SUPPORT").steps(Collections.emptyList()).build();
        }

        // Convert steps → FlowFieldConfig list for storage
        List<FlowFieldConfig> fields = stepsToFieldConfigs(jsonConfig.getSteps());

        TenantFlowConfigJson jsonWrapper = TenantFlowConfigJson.builder()
                .greetingMessage(jsonConfig.getGreetingMessage())
                .fields(fields)
                .build();

        try {
            TenantFlowConfig newRow = TenantFlowConfig.builder()
                    .tenant(user)
                    .flowType(ConversationState.FlowType.SUPPORT)
                    .configurationJson(objectMapper.writeValueAsString(jsonWrapper))
                    .templateVersion(1)
                    .build();
            tenantFlowConfigRepository.save(newRow);
            log.info("[FlowConfigService] Seeded SUPPORT flow ({} steps) to DB for tenant={}",
                    fields.size(), user.getId());
        } catch (Exception e) {
            log.error("[FlowConfigService] Failed to seed SUPPORT flow to DB for tenant={}: {}",
                    user.getId(), e.getMessage());
        }

        // Return the JSON-backed config directly (seeded version)
        jsonConfig.setFlowType("SUPPORT");
        return jsonConfig;
    }

    /**
     * Builds a FlowConfigDTO from a tenant's persisted TenantFlowConfig DB row.
     * Only enabled steps are included, sorted by their configured order.
     */
    private FlowConfigDTO buildSupportConfigFromDbRow(TenantFlowConfig row) {
        try {
            TenantFlowConfigJson configJson = objectMapper.readValue(row.getConfigurationJson(), TenantFlowConfigJson.class);
            if (configJson == null || configJson.getFields() == null) {
                log.warn("[FlowConfigService] SUPPORT DB row for tenant={} has null/empty fields", row.getTenant().getId());
                return FlowConfigDTO.builder().flowType("SUPPORT").steps(Collections.emptyList()).build();
            }

            List<FlowStepDTO> steps = configJson.getFields().stream()
                    .filter(FlowFieldConfig::isEnabled)
                    .sorted(Comparator.comparingInt(FlowFieldConfig::getOrder))
                    .map(this::fieldConfigToStep)
                    .collect(Collectors.toList());

            return FlowConfigDTO.builder()
                    .flowType("SUPPORT")
                    .greetingMessage(configJson.getGreetingMessage())
                    .steps(steps)
                    .build();
        } catch (Exception e) {
            log.error("[FlowConfigService] Failed to parse SUPPORT flow config from DB for tenant={}: {}",
                    row.getTenant().getId(), e.getMessage());
            return FlowConfigDTO.builder().flowType("SUPPORT").steps(Collections.emptyList()).build();
        }
    }

    /**
     * DB-first flow config loader for APPOINTMENT, BOOKING, LEAD_CAPTURE, ENQUIRY.
     *
     * Flow:
     *  1. Check DB for tenant's saved config  → return it directly (only enabled fields)
     *  2. No DB row → seed from master-fields.json filtered by defaultEnabled + niche
     *     Save the seed to DB so next call hits DB
     *  3. Send greeting from DB config (if set)
     */
    @Transactional
    private FlowConfigDTO getOrSeedFlowConfig(User user, ConversationState.FlowType flowType) {
        Optional<TenantFlowConfig> dbConfigOpt = tenantFlowConfigRepository.findByTenantAndFlowType(user, flowType);

        if (dbConfigOpt.isPresent()) {
            TenantFlowConfig dbConfig = dbConfigOpt.get();
            try {
                TenantFlowConfigJson configJson = objectMapper.readValue(dbConfig.getConfigurationJson(), TenantFlowConfigJson.class);

                String greetingMessage = configJson != null ? configJson.getGreetingMessage() : null;

                if (configJson != null && configJson.getFields() != null && !configJson.getFields().isEmpty()) {
                    java.util.Set<String> seenKeys = new java.util.LinkedHashSet<>();
                    List<FlowStepDTO> steps = configJson.getFields().stream()
                            .filter(FlowFieldConfig::isEnabled)
                            .sorted(Comparator.comparingInt(FlowFieldConfig::getOrder))
                            .map(fc -> {
                                if (fc.getKey() == null || fc.getKey().isBlank() || seenKeys.contains(fc.getKey())) {
                                    fc.setKey((fc.getKey() != null && !fc.getKey().isBlank() ? fc.getKey() : "field") + "_" + fc.getOrder());
                                }
                                seenKeys.add(fc.getKey());
                                return fc;
                            })
                            .map(this::fieldConfigToStep)
                            .collect(Collectors.toList());

                    log.info("[FlowConfigService] Loaded {} enabled steps from DB for tenant={} flowType={}",
                            steps.size(), user.getId(), flowType);

                    return FlowConfigDTO.builder()
                            .flowType(flowType.name())
                            .greetingMessage(greetingMessage)
                            .steps(steps)
                            .build();
                }
            } catch (Exception e) {
                log.error("[FlowConfigService] Failed to parse DB config for tenant={} flowType={}: {}",
                        user.getId(), flowType, e.getMessage());
            }
        }

        // ── No DB config: seed from master-fields.json (first-time only) ─────────
        log.info("[FlowConfigService] No DB config for tenant={} flowType={} — seeding from master-fields.json", user.getId(), flowType);
        FlowConfigDTO masterConfig = loadFlow("master-fields");
        if (masterConfig == null) {
            log.error("[FlowConfigService] master-fields.json missing from classpath — returning empty config");
            return FlowConfigDTO.builder().flowType(flowType.name()).steps(Collections.emptyList()).build();
        }

        String subCat = user.getBusinessSubType();
        int order = 0;
        List<FlowFieldConfig> seedFields = new ArrayList<>();

        for (FlowStepDTO step : masterConfig.getSteps()) {
            // Skip niche-specific fields that don't match this tenant
            if (step.getApplicableNiches() != null && !step.getApplicableNiches().isEmpty()) {
                if (subCat == null || !step.getApplicableNiches().contains(subCat)) {
                    continue;
                }
            }
            seedFields.add(FlowFieldConfig.builder()
                    .key(step.getDataKey())
                    .label(step.getQuestion())
                    .fieldType(step.getFieldType())
                    .required(step.isRequired())
                    .enabled(step.isDefaultEnabled())
                    .order(step.getDisplayOrder() != null ? step.getDisplayOrder() : order)
                    .options(step.getOptions())
                    .build());
            order++;
        }

        // Save seed to DB so future calls come from DB
        // Set a sensible default greeting message during first-time seed
        String defaultGreeting = "👋 Hello {{contact.firstName}}!\n\nThank you for reaching out. Let us gather a few details to assist you.";
        try {
            // Save with default greeting
            Optional<TenantFlowConfig> existing = tenantFlowConfigRepository.findByTenantAndFlowType(user, flowType);
            TenantFlowConfigJson jsonWrapper = new TenantFlowConfigJson();
            jsonWrapper.setGreetingMessage(defaultGreeting);
            jsonWrapper.setFields(seedFields);
            if (existing.isPresent()) {
                existing.get().setConfigurationJson(objectMapper.writeValueAsString(jsonWrapper));
                tenantFlowConfigRepository.save(existing.get());
            } else {
                TenantFlowConfig newRow = TenantFlowConfig.builder()
                        .tenant(user)
                        .flowType(flowType)
                        .configurationJson(objectMapper.writeValueAsString(jsonWrapper))
                        .templateVersion(1)
                        .build();
                tenantFlowConfigRepository.save(newRow);
            }
            log.info("[FlowConfigService] Auto-seeded {} fields + default greeting to DB for tenant={} flowType={}",
                    seedFields.size(), user.getId(), flowType);
        } catch (Exception e) {
            log.warn("[FlowConfigService] Failed to auto-seed fields for tenant={}: {}", user.getId(), e.getMessage());
        }

        // Return only defaultEnabled=true fields for first-time flow execution
        List<FlowStepDTO> steps = seedFields.stream()
                .filter(FlowFieldConfig::isEnabled)
                .sorted(Comparator.comparingInt(FlowFieldConfig::getOrder))
                .map(this::fieldConfigToStep)
                .collect(Collectors.toList());

        return FlowConfigDTO.builder()
                .flowType(flowType.name())
                .greetingMessage(defaultGreeting)
                .steps(steps)
                .build();
    }

    /**
     * Converts a FlowFieldConfig (DB storage format) → FlowStepDTO (runtime format).
     */
    private FlowStepDTO fieldConfigToStep(FlowFieldConfig fc) {
        String key = fc.getKey() != null ? fc.getKey().toLowerCase().replaceAll("_\\d+$", "") : "";
        boolean isCategoryKey = key.contains("category");
        boolean isServiceKey = key.contains("service") || key.contains("treatment") || key.contains("consultation");
        boolean isDynamicKey = isCategoryKey || isServiceKey;

        boolean isDropdownType = "DROPDOWN".equalsIgnoreCase(fc.getFieldType())
                || "SELECT".equalsIgnoreCase(fc.getFieldType())
                || "BUTTON".equalsIgnoreCase(fc.getFieldType())
                || "DYNAMIC_DROPDOWN".equalsIgnoreCase(fc.getFieldType())
                || "DYNAMIC_SELECT".equalsIgnoreCase(fc.getFieldType());

        // Resolve OptionSource
        FlowFieldConfig.OptionSource resolvedSource = fc.getOptionSource();
        if (resolvedSource == null || resolvedSource == FlowFieldConfig.OptionSource.AUTO_DETECT) {
            if (isCategoryKey) {
                resolvedSource = FlowFieldConfig.OptionSource.DYNAMIC_CATEGORIES;
            } else if (isServiceKey) {
                resolvedSource = FlowFieldConfig.OptionSource.DYNAMIC_SERVICES;
            } else if (isDropdownType && (fc.getOptions() == null || fc.getOptions().isEmpty())) {
                resolvedSource = FlowFieldConfig.OptionSource.DYNAMIC_SERVICES;
            } else {
                resolvedSource = FlowFieldConfig.OptionSource.STATIC;
            }
        }

        boolean dynamicSource = resolvedSource == FlowFieldConfig.OptionSource.DYNAMIC_SERVICES 
                || resolvedSource == FlowFieldConfig.OptionSource.DYNAMIC_CATEGORIES;
        boolean usesButtons = (fc.getOptions() != null && !fc.getOptions().isEmpty() && isDropdownType) || dynamicSource;

        return FlowStepDTO.builder()
                .dataKey(fc.getKey())
                .question(fc.getLabel() != null ? fc.getLabel() : fc.getKey())
                .fieldType(fc.getFieldType())
                .required(fc.isRequired())
                .defaultEnabled(fc.isEnabled())
                .displayOrder(fc.getOrder())
                .options(fc.getOptions() != null ? fc.getOptions() : new ArrayList<>())
                .usesButtons(usesButtons)
                .dynamicSource(dynamicSource)
                .optionSource(resolvedSource)
                .applicableNiches(new ArrayList<>())
                .build();
    }

    /**
     * Converts FlowStepDTO list (JSON/runtime format) → FlowFieldConfig list (DB storage format).
     */
    private List<FlowFieldConfig> stepsToFieldConfigs(List<FlowStepDTO> steps) {
        if (steps == null) return new ArrayList<>();
        List<FlowFieldConfig> result = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            FlowStepDTO step = steps.get(i);
            result.add(FlowFieldConfig.builder()
                    .key(step.getDataKey())
                    .label(step.getQuestion())
                    .fieldType(step.getFieldType())
                    .required(step.isRequired())
                    .enabled(step.isDefaultEnabled())
                    .order(step.getDisplayOrder() != null ? step.getDisplayOrder() : i)
                    .options(step.getOptions())
                    .build());
        }
        return result;
    }

    private FlowConfigDTO applyTenantConfiguration(User tenant, ConversationState.FlowType flowType, FlowConfigDTO masterConfig) {
        // SUPPORT is now handled entirely by getOrSeedSupportFlowConfig() — never reaches here.
        if (flowType == ConversationState.FlowType.SUPPORT) {
            return masterConfig;
        }

        Optional<TenantFlowConfig> dbConfigOpt = findDbConfigWithFallback(tenant, flowType);
        String subCat = tenant != null ? tenant.getBusinessSubType() : null;

        if (dbConfigOpt.isPresent()) {
            TenantFlowConfig dbConfig = dbConfigOpt.get();
            try {
                TenantFlowConfigJson configJson = objectMapper.readValue(dbConfig.getConfigurationJson(), TenantFlowConfigJson.class);
                
                if (configJson != null && configJson.getGreetingMessage() != null) {
                    masterConfig.setGreetingMessage(configJson.getGreetingMessage());
                }

                // If no greeting in the resolved config, try fetching it from the specific flowType config
                if ((masterConfig.getGreetingMessage() == null || masterConfig.getGreetingMessage().isBlank())
                        && dbConfig.getFlowType() != flowType) {
                    Optional<TenantFlowConfig> specificOpt = tenantFlowConfigRepository.findByTenantAndFlowType(tenant, flowType);
                    if (specificOpt.isPresent()) {
                        try {
                            TenantFlowConfigJson specificJson = objectMapper.readValue(specificOpt.get().getConfigurationJson(), TenantFlowConfigJson.class);
                            if (specificJson != null && specificJson.getGreetingMessage() != null) {
                                masterConfig.setGreetingMessage(specificJson.getGreetingMessage());
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (configJson != null && configJson.getFields() != null && !configJson.getFields().isEmpty()) {
                    // DB row exists and has fields configured by tenant — build runtime steps directly from tenant's fields
                    // Deduplicate by dataKey to prevent duplicate questions in WhatsApp flow
                    java.util.Set<String> seenKeys = new java.util.LinkedHashSet<>();
                    List<FlowStepDTO> steps = configJson.getFields().stream()
                            .filter(FlowFieldConfig::isEnabled)
                            .sorted(Comparator.comparingInt(FlowFieldConfig::getOrder))
                            .map(fc -> {
                                if (fc.getKey() == null || fc.getKey().isBlank() || seenKeys.contains(fc.getKey())) {
                                    fc.setKey((fc.getKey() != null && !fc.getKey().isBlank() ? fc.getKey() : "field") + "_" + fc.getOrder());
                                }
                                seenKeys.add(fc.getKey());
                                return fc;
                            })
                            .map(this::fieldConfigToStep)
                            .collect(Collectors.toList());

                    if (!steps.isEmpty()) {
                        masterConfig.setSteps(steps);
                        return masterConfig;
                    }
                }
            } catch (Exception e) {
                log.error("[FlowConfigService] Failed to parse TenantFlowConfig JSON for tenant: {}", tenant.getId(), e);
            }
        }

        // Fallback or No DB Config: Keep defaultEnabled == true, filter by applicableNiches, sort by displayOrder
        List<FlowStepDTO> filteredSteps = new ArrayList<>();
        for (FlowStepDTO step : masterConfig.getSteps()) {
            if (step.isDefaultEnabled()) {
                if (step.getApplicableNiches() != null && !step.getApplicableNiches().isEmpty()) {
                    if (subCat == null || !step.getApplicableNiches().contains(subCat)) {
                        continue;
                    }
                }
                filteredSteps.add(step);
            }
        }
        filteredSteps.sort(Comparator.comparingInt(s -> s.getDisplayOrder() != null ? s.getDisplayOrder() : 999));
        masterConfig.setSteps(filteredSteps);
        return masterConfig;
    }

    private FlowConfigDTO applySupportFormConfiguration(User tenant, FlowConfigDTO masterConfig) {
        Optional<SupportFormConfig> dbConfigOpt = supportFormConfigRepository.findByOwner(tenant);
        if (dbConfigOpt.isEmpty()) {
            return masterConfig;
        }
        SupportFormConfig config = dbConfigOpt.get();
        List<FlowStepDTO> steps = masterConfig.getSteps();
        
        for (FlowStepDTO step : steps) {
            if ("phone".equals(step.getDataKey())) {
                step.setRequired(config.isPhoneRequired());
            } else if ("category".equals(step.getDataKey())) {
                step.setRequired(config.isCategoryRequired());
                String cats = config.getCategories();
                if (cats != null && !cats.isBlank()) {
                    List<String> options = Arrays.stream(cats.split(","))
                            .map(String::trim).filter(s -> !s.isEmpty()).toList();
                    if (!options.isEmpty()) {
                        step.setOptions(options);
                        step.setUsesButtons(true);
                        step.setFieldType("DROPDOWN");
                    }
                }
            }
        }
        return masterConfig;
    }

    
    public List<FlowFieldConfig> getConfigurableFields(User user, String explicitSuffix) {
        ConversationState.FlowType flowTypeEnum = resolveFlowTypeEnum(user, explicitSuffix);

        // ── SUPPORT FLOW: DB is primary — auto-seed if needed ─────────────────
        if (flowTypeEnum == ConversationState.FlowType.SUPPORT) {
            Optional<TenantFlowConfig> dbRowOpt =
                    tenantFlowConfigRepository.findByTenantAndFlowType(user, ConversationState.FlowType.SUPPORT);

            if (dbRowOpt.isPresent()) {
                // Return all fields (enabled + disabled) sorted by order for the edit UI
                try {
                    TenantFlowConfigJson configJson = objectMapper.readValue(
                            dbRowOpt.get().getConfigurationJson(), TenantFlowConfigJson.class);
                    if (configJson != null && configJson.getFields() != null && !configJson.getFields().isEmpty()) {
                        List<FlowFieldConfig> sorted = new ArrayList<>(configJson.getFields());
                        sorted.sort(Comparator.comparingInt(FlowFieldConfig::getOrder));
                        return sorted;
                    }
                } catch (Exception e) {
                    log.error("[FlowConfigService] Failed to parse SUPPORT config for tenant={}: {}",
                            user.getId(), e.getMessage());
                }
            }

            // No DB row — trigger auto-seed by calling getOrSeedSupportFlowConfig,
            // then return the seeded fields
            log.info("[FlowConfigService] Auto-seeding SUPPORT fields for tenant={}", user.getId());
            getOrSeedSupportFlowConfig(user);

            // Re-fetch after seed
            return tenantFlowConfigRepository.findByTenantAndFlowType(user, ConversationState.FlowType.SUPPORT)
                    .map(row -> {
                        try {
                            TenantFlowConfigJson cfg = objectMapper.readValue(row.getConfigurationJson(), TenantFlowConfigJson.class);
                            if (cfg != null && cfg.getFields() != null) {
                                List<FlowFieldConfig> sorted = new ArrayList<>(cfg.getFields());
                                sorted.sort(Comparator.comparingInt(FlowFieldConfig::getOrder));
                                return sorted;
                            }
                        } catch (Exception e) {
                            log.error("[FlowConfigService] Parse error after seed for tenant={}", user.getId(), e);
                        }
                        return Collections.<FlowFieldConfig>emptyList();
                    })
                    .orElse(Collections.emptyList());
        }

        // ── ALL NON-SUPPORT FLOWS: DB is primary source of truth ─────────────
        // If the tenant has already saved fields via the UI, return those directly.
        // Only fall back to master-fields.json to seed the initial default config.
        Optional<TenantFlowConfig> dbConfigOpt = findDbConfigWithFallback(user, flowTypeEnum);

        if (dbConfigOpt.isPresent()) {
            try {
                TenantFlowConfigJson configJson = objectMapper.readValue(
                        dbConfigOpt.get().getConfigurationJson(), TenantFlowConfigJson.class);
                if (configJson != null && configJson.getFields() != null && !configJson.getFields().isEmpty()) {
                    // Ensure all keys are unique without dropping custom fields
                    java.util.Set<String> seenKeys = new java.util.LinkedHashSet<>();
                    List<FlowFieldConfig> deduped = new ArrayList<>();
                    for (FlowFieldConfig fc : configJson.getFields()) {
                        if (fc.getKey() == null || fc.getKey().isBlank() || seenKeys.contains(fc.getKey())) {
                            fc.setKey((fc.getKey() != null && !fc.getKey().isBlank() ? fc.getKey() : "field") + "_" + fc.getOrder());
                        }
                        seenKeys.add(fc.getKey());
                        deduped.add(fc);
                    }
                    deduped.sort(Comparator.comparingInt(FlowFieldConfig::getOrder));
                    log.debug("[FlowConfigService] Returning {} DB-stored fields for tenant={} flowType={}",
                            deduped.size(), user.getId(), flowTypeEnum);
                    return deduped;
                }
            } catch (Exception e) {
                log.error("[FlowConfigService] Failed to parse config for tenant={} flowType={}: {}",
                        user.getId(), flowTypeEnum, e.getMessage());
            }
        }

        // ── No DB config yet: seed from master-fields.json (first-time only) ──────
        // master-fields.json is the single source of truth for all flow types.
        // The enabled/disabled state is controlled per tenant via DB after first seed.
        String seedSlug = "master-fields";
        FlowConfigDTO masterConfig = loadFlow(seedSlug);
        if (masterConfig == null) {
            return Collections.emptyList();
        }

        List<FlowFieldConfig> result = new ArrayList<>();
        String subCat = user.getBusinessSubType();
        int defaultOrder = 0;

        for (FlowStepDTO step : masterConfig.getSteps()) {
            if (step.getApplicableNiches() != null && !step.getApplicableNiches().isEmpty()) {
                if (subCat == null || !step.getApplicableNiches().contains(subCat)) {
                    continue;
                }
            }

            result.add(FlowFieldConfig.builder()
                    .key(step.getDataKey())
                    .enabled(step.isDefaultEnabled())
                    .required(step.isRequired())
                    .order(step.getDisplayOrder() != null ? step.getDisplayOrder() : defaultOrder)
                    .label(step.getQuestion())
                    .fieldType(step.getFieldType())
                    .options(step.getOptions())
                    .build());
            defaultOrder++;
        }

        result.sort(Comparator.comparingInt(FlowFieldConfig::getOrder));

        // Auto-seed: save these defaults to DB so future fetches come from DB
        try {
            saveOrUpdateFieldsInternal(user, flowTypeEnum, result);
            log.info("[FlowConfigService] Auto-seeded {} fields to DB for tenant={} flowType={}",
                    result.size(), user.getId(), flowTypeEnum);
        } catch (Exception e) {
            log.warn("[FlowConfigService] Failed to auto-seed fields for tenant={}: {}", user.getId(), e.getMessage());
        }

        return result;
    }

    @Transactional
    public void saveConfigurableFields(User user, String explicitSuffix, List<FlowFieldConfig> fields) {
        if (fields == null || fields.isEmpty()) {
            log.warn("[FlowConfigService] Attempted to save empty flow fields for user={}, ignoring", user != null ? user.getEmail() : "unknown");
            return;
        }
        ConversationState.FlowType flowTypeEnum = resolveFlowTypeEnum(user, explicitSuffix);

        try {
            saveOrUpdateFieldsInternal(user, flowTypeEnum, fields);
            log.info("Saved configurable fields for user: {} flowType: {}", user.getEmail(), flowTypeEnum);
        } catch (Exception e) {
            log.error("Error saving configurable fields", e);
            throw new RuntimeException("Failed to save configuration", e);
        }
    }

    private void saveOrUpdateFieldsInternal(User user, ConversationState.FlowType flowTypeEnum, List<FlowFieldConfig> fields) throws Exception {
        Optional<TenantFlowConfig> dbConfigOpt = tenantFlowConfigRepository.findByTenantAndFlowType(user, flowTypeEnum);
        TenantFlowConfig dbConfig;
        TenantFlowConfigJson jsonConfig = new TenantFlowConfigJson();
        jsonConfig.setFields(fields);

        if (dbConfigOpt.isPresent()) {
            dbConfig = dbConfigOpt.get();
            try {
                TenantFlowConfigJson existingJson = objectMapper.readValue(dbConfig.getConfigurationJson(), TenantFlowConfigJson.class);
                if (existingJson != null) {
                    jsonConfig.setGreetingMessage(existingJson.getGreetingMessage());
                }
            } catch (Exception e) {
                log.error("Failed to parse existing config", e);
            }
            dbConfig.setConfigurationJson(objectMapper.writeValueAsString(jsonConfig));
            tenantFlowConfigRepository.save(dbConfig);
        } else {
            try {
                dbConfig = TenantFlowConfig.builder()
                        .tenant(user)
                        .flowType(flowTypeEnum)
                        .configurationJson(objectMapper.writeValueAsString(jsonConfig))
                        .templateVersion(1)
                        .build();
                tenantFlowConfigRepository.save(dbConfig);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                log.warn("Concurrent insert race condition for tenant flow config, retrying update");
                TenantFlowConfig existing = tenantFlowConfigRepository.findByTenantAndFlowType(user, flowTypeEnum)
                        .orElseThrow(() -> ex);
                try {
                    TenantFlowConfigJson existingJson = objectMapper.readValue(existing.getConfigurationJson(), TenantFlowConfigJson.class);
                    if (existingJson != null) {
                        jsonConfig.setGreetingMessage(existingJson.getGreetingMessage());
                    }
                } catch (Exception e) {
                    log.error("Failed to parse existing config", e);
                }
                existing.setConfigurationJson(objectMapper.writeValueAsString(jsonConfig));
                tenantFlowConfigRepository.save(existing);
            }
        }
    }

    public String getFlowGreeting(User user, String explicitSuffix) {
        ConversationState.FlowType flowTypeEnum = resolveFlowTypeEnum(user, explicitSuffix);
        // For APPOINTMENT, BOOKING, LEAD_CAPTURE — use direct DB lookup (no ENQUIRY fallback)
        Optional<TenantFlowConfig> dbConfigOpt = tenantFlowConfigRepository.findByTenantAndFlowType(user, flowTypeEnum);
        if (dbConfigOpt.isEmpty() && flowTypeEnum != ConversationState.FlowType.APPOINTMENT
                && flowTypeEnum != ConversationState.FlowType.BOOKING) {
            dbConfigOpt = findDbConfigWithFallback(user, flowTypeEnum);
        }
        if (dbConfigOpt.isPresent()) {
            try {
                TenantFlowConfigJson configJson = objectMapper.readValue(dbConfigOpt.get().getConfigurationJson(), TenantFlowConfigJson.class);
                if (configJson != null && configJson.getGreetingMessage() != null
                        && !configJson.getGreetingMessage().isBlank()) {
                    return configJson.getGreetingMessage();
                }
            } catch (Exception e) {
                log.error("Failed to parse config json", e);
            }
        }
        // Return default greeting if none set yet
        return "👋 Hello {{contact.firstName}}!\n\nThank you for reaching out. Let us gather a few details to assist you.";
    }

    @Transactional
    public void saveFlowGreeting(User user, String explicitSuffix, String greetingMessage) {
        ConversationState.FlowType flowTypeEnum = resolveFlowTypeEnum(user, explicitSuffix);
        try {
            saveOrUpdateGreetingInternal(user, flowTypeEnum, greetingMessage);
        } catch (Exception e) {
            log.error("Failed to save greeting message for user={} flowType={}", user.getEmail(), flowTypeEnum, e);
            throw new RuntimeException("Failed to save greeting message", e);
        }
    }

    private void saveOrUpdateGreetingInternal(User user, ConversationState.FlowType flowTypeEnum, String greetingMessage) throws Exception {
        Optional<TenantFlowConfig> dbConfigOpt = tenantFlowConfigRepository.findByTenantAndFlowType(user, flowTypeEnum);
        TenantFlowConfig dbConfig;
        TenantFlowConfigJson jsonConfig = new TenantFlowConfigJson();

        if (dbConfigOpt.isPresent()) {
            dbConfig = dbConfigOpt.get();
            try {
                TenantFlowConfigJson existingJson = objectMapper.readValue(dbConfig.getConfigurationJson(), TenantFlowConfigJson.class);
                if (existingJson != null) {
                    jsonConfig.setFields(existingJson.getFields());
                }
            } catch (Exception e) {
                log.error("Failed to parse existing config", e);
            }
            jsonConfig.setGreetingMessage(greetingMessage);
            dbConfig.setConfigurationJson(objectMapper.writeValueAsString(jsonConfig));
            tenantFlowConfigRepository.save(dbConfig);
        } else {
            try {
                jsonConfig.setGreetingMessage(greetingMessage);
                dbConfig = TenantFlowConfig.builder()
                        .tenant(user)
                        .flowType(flowTypeEnum)
                        .configurationJson(objectMapper.writeValueAsString(jsonConfig))
                        .templateVersion(1)
                        .build();
                tenantFlowConfigRepository.save(dbConfig);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                log.warn("Concurrent insert race condition for tenant flow config greeting, retrying update");
                TenantFlowConfig existing = tenantFlowConfigRepository.findByTenantAndFlowType(user, flowTypeEnum)
                        .orElseThrow(() -> ex);
                try {
                    TenantFlowConfigJson existingJson = objectMapper.readValue(existing.getConfigurationJson(), TenantFlowConfigJson.class);
                    if (existingJson != null) {
                        jsonConfig.setFields(existingJson.getFields());
                    }
                } catch (Exception e) {
                    log.error("Failed to parse existing config", e);
                }
                jsonConfig.setGreetingMessage(greetingMessage);
                existing.setConfigurationJson(objectMapper.writeValueAsString(jsonConfig));
                tenantFlowConfigRepository.save(existing);
            }
        }
    }

    @Transactional
    public void deleteFlowConfig(User user, String explicitSuffix) {
        ConversationState.FlowType flowTypeEnum = resolveFlowTypeEnum(user, explicitSuffix);
        tenantFlowConfigRepository.findByTenantAndFlowType(user, flowTypeEnum)
                .ifPresent(tenantFlowConfigRepository::delete);
    }

    private ConversationState.FlowType resolveFlowTypeEnum(User user, String explicitSuffix) {
        if (explicitSuffix != null && !explicitSuffix.isBlank()) {
            String flowTypeStr = explicitSuffix.toLowerCase();
            if ("appointment".equals(flowTypeStr)) return ConversationState.FlowType.APPOINTMENT;
            if ("booking".equals(flowTypeStr)) return ConversationState.FlowType.BOOKING;
            if ("lead".equals(flowTypeStr) || "lead_capture".equals(flowTypeStr)) return ConversationState.FlowType.LEAD_CAPTURE;
            if ("support".equals(flowTypeStr)) return ConversationState.FlowType.SUPPORT;
            
            try {
                return ConversationState.FlowType.valueOf(explicitSuffix.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Ignore and fall through to LEAD_CAPTURE
            }
            return ConversationState.FlowType.LEAD_CAPTURE;
        }
        
        if (user != null) {
            if (Boolean.TRUE.equals(user.getForceShowAppointment())) return ConversationState.FlowType.APPOINTMENT;
            if (Boolean.TRUE.equals(user.getForceShowBooking())) return ConversationState.FlowType.BOOKING;
            if (Boolean.TRUE.equals(user.getForceShowLeads())) return ConversationState.FlowType.LEAD_CAPTURE;
            
            FlowTemplateEngine.FlowBlueprint blueprint = flowTemplateEngine.getBlueprint(user.getBusinessSubType());
            if (blueprint != null && blueprint.getFlowType() != null) {
                ConversationState.FlowType bpType = blueprint.getFlowType();
                if (bpType == ConversationState.FlowType.ENQUIRY) {
                    return ConversationState.FlowType.LEAD_CAPTURE;
                }
                return bpType;
            }
        }
        
        return ConversationState.FlowType.LEAD_CAPTURE;
    }

    private FlowConfigDTO loadFlow(String slug) {
        String path = "/flows/" + slug + ".json";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                log.debug("[FlowConfigService] Resource not found: {}", path);
                return null;
            }
            FlowConfigDTO dto = objectMapper.readValue(is, FlowConfigDTO.class);
            log.debug("[FlowConfigService] Loaded flow '{}' with {} steps", slug,
                    dto.getSteps() != null ? dto.getSteps().size() : 0);
            return dto;
        } catch (Exception e) {
            log.error("[FlowConfigService] Failed to parse flow '{}': {}", slug, e.getMessage());
            return null;
        }
    }

    private Optional<TenantFlowConfig> findDbConfigWithFallback(User user, ConversationState.FlowType flowType) {
        Optional<TenantFlowConfig> dbConfigOpt = tenantFlowConfigRepository.findByTenantAndFlowType(user, flowType);

        // Backward compatibility: If the current resolved flow type has no config,
        // fall back to ENQUIRY since older versions always saved UI configs to ENQUIRY.
        // IMPORTANT: Do NOT fall back for APPOINTMENT or BOOKING — those flows require
        // date/time fields that ENQUIRY configs do not contain. Falling back would cause
        // appointment bookings with no user-provided date (always defaulting to tomorrow 10AM).
        if (dbConfigOpt.isEmpty()
                && flowType != ConversationState.FlowType.ENQUIRY
                && flowType != ConversationState.FlowType.APPOINTMENT
                && flowType != ConversationState.FlowType.BOOKING) {
            Optional<TenantFlowConfig> fallbackOpt = tenantFlowConfigRepository.findByTenantAndFlowType(user, ConversationState.FlowType.ENQUIRY);
            if (fallbackOpt.isPresent()) {
                log.info("[FlowConfigService] Falling back to ENQUIRY config for tenant={} since {} is empty", user.getId(), flowType);
                return fallbackOpt;
            }
        }
        return dbConfigOpt;
    }
}

