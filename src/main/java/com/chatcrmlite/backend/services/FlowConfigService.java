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

    @Autowired
    public FlowConfigService(ObjectMapper objectMapper, TenantFlowConfigRepository tenantFlowConfigRepository, SupportFormConfigRepository supportFormConfigRepository) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.tenantFlowConfigRepository = tenantFlowConfigRepository;
        this.supportFormConfigRepository = supportFormConfigRepository;
    }

    public FlowConfigDTO getFlowConfig(User user) {
        return getFlowConfig(user, null);
    }

    @Transactional
    public FlowConfigDTO getFlowConfig(User user, String explicitSuffix) {
        String flowTypeStr = "";
        ConversationState.FlowType flowTypeEnum = ConversationState.FlowType.ENQUIRY;

        if (explicitSuffix != null && !explicitSuffix.isBlank()) {
            flowTypeStr = explicitSuffix.toLowerCase();
        } else if (user != null) {
            if (Boolean.TRUE.equals(user.getForceShowAppointment())) {
                flowTypeStr = "appointment";
            } else if (Boolean.TRUE.equals(user.getForceShowBooking())) {
                flowTypeStr = "booking";
            } else if (Boolean.TRUE.equals(user.getForceShowLeads())) {
                flowTypeStr = "lead";
            }
        }

        if ("appointment".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.APPOINTMENT;
        } else if ("booking".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.BOOKING;
        } else if ("lead".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.LEAD_CAPTURE;
        } else if ("support".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.SUPPORT;
        }

        // ── SUPPORT FLOW: DB is the primary source of truth ──────────────────
        // On first access for this tenant, auto-seed from support.json.
        // Subsequent accesses use the tenant's own DB row (which they can edit).
        if (flowTypeEnum == ConversationState.FlowType.SUPPORT && user != null) {
            return getOrSeedSupportFlowConfig(user);
        }

        // ── Non-SUPPORT flows: load from classpath JSON ───────────────────────
        String masterSlug = "master-fields";
        FlowConfigDTO config = loadFlow(masterSlug);

        if (config == null) {
            log.warn("[FlowConfigService] No master flow found for '{}', falling back to generic", masterSlug);
            config = loadFlow("generic");
        }

        if (config == null) {
            log.error("[FlowConfigService] generic.json missing from classpath — returning empty config");
            return FlowConfigDTO.builder().flowType("ENQUIRY").build();
        }

        config.setFlowType(flowTypeEnum.name());

        if (user != null) {
            config = applyTenantConfiguration(user, flowTypeEnum, config);
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
     * Converts a FlowFieldConfig (DB storage format) → FlowStepDTO (runtime format).
     */
    private FlowStepDTO fieldConfigToStep(FlowFieldConfig fc) {
        boolean usesButtons = fc.getOptions() != null && !fc.getOptions().isEmpty()
                && ("DROPDOWN".equalsIgnoreCase(fc.getFieldType()) || "BUTTON".equalsIgnoreCase(fc.getFieldType()));
        return FlowStepDTO.builder()
                .dataKey(fc.getKey())
                .question(fc.getLabel() != null ? fc.getLabel() : fc.getKey())
                .fieldType(fc.getFieldType())
                .required(fc.isRequired())
                .defaultEnabled(fc.isEnabled())
                .displayOrder(fc.getOrder())
                .options(fc.getOptions() != null ? fc.getOptions() : new ArrayList<>())
                .usesButtons(usesButtons)
                .dynamicSource(false)
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

        Optional<TenantFlowConfig> dbConfigOpt = tenantFlowConfigRepository.findByTenantAndFlowType(tenant, flowType);
        
        List<FlowStepDTO> filteredSteps = new ArrayList<>();

        if (dbConfigOpt.isEmpty()) {
            // No DB config: Keep only defaultEnabled == true, sort by displayOrder
            for (FlowStepDTO step : masterConfig.getSteps()) {
                if (step.isDefaultEnabled()) {
                    filteredSteps.add(step);
                }
            }
            filteredSteps.sort(Comparator.comparingInt(s -> s.getDisplayOrder() != null ? s.getDisplayOrder() : 999));
            masterConfig.setSteps(filteredSteps);
            return masterConfig;
        }

        TenantFlowConfig dbConfig = dbConfigOpt.get();
        try {
            TenantFlowConfigJson configJson = objectMapper.readValue(dbConfig.getConfigurationJson(), TenantFlowConfigJson.class);
            
            if (configJson != null && configJson.getGreetingMessage() != null) {
                masterConfig.setGreetingMessage(configJson.getGreetingMessage());
            }

            if (configJson == null || configJson.getFields() == null) {
                // If invalid JSON, fallback to default behavior
                for (FlowStepDTO step : masterConfig.getSteps()) {
                    if (step.isDefaultEnabled()) {
                        filteredSteps.add(step);
                    }
                }
                filteredSteps.sort(Comparator.comparingInt(s -> s.getDisplayOrder() != null ? s.getDisplayOrder() : 999));
                masterConfig.setSteps(filteredSteps);
                return masterConfig;
            }

            Map<String, FlowFieldConfig> fieldConfigMap = configJson.getFields().stream()
                    .collect(Collectors.toMap(FlowFieldConfig::getKey, f -> f));

            for (FlowStepDTO step : masterConfig.getSteps()) {
                FlowFieldConfig fieldConfig = fieldConfigMap.get(step.getDataKey());
                
                // If the field is not in the configuration or is explicitly disabled, skip it.
                if (fieldConfig == null || !fieldConfig.isEnabled()) {
                    continue;
                }

                // Override required property
                step.setRequired(fieldConfig.isRequired());

                // Apply custom label if present
                if (fieldConfig.getLabel() != null && !fieldConfig.getLabel().isBlank()) {
                    step.setQuestion(fieldConfig.getLabel());
                }

                // Apply custom options if it's a dropdown and options are present
                if ("DROPDOWN".equalsIgnoreCase(step.getFieldType()) || step.isUsesButtons()) {
                    if (fieldConfig.getOptions() != null && !fieldConfig.getOptions().isEmpty()) {
                        step.setOptions(fieldConfig.getOptions());
                        step.setUsesButtons(true);
                    }
                }

                filteredSteps.add(step);
            }

            // Sort steps based on the configured order
            filteredSteps.sort(Comparator.comparingInt(s -> {
                FlowFieldConfig fc = fieldConfigMap.get(s.getDataKey());
                return fc != null ? fc.getOrder() : 999;
            }));

            masterConfig.setSteps(filteredSteps);

        } catch (Exception e) {
            log.error("[FlowConfigService] Failed to parse TenantFlowConfig JSON for tenant: {}", tenant.getId(), e);
        }

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
        String flowTypeStr = "";
        ConversationState.FlowType flowTypeEnum = ConversationState.FlowType.ENQUIRY;

        if (explicitSuffix != null && !explicitSuffix.isBlank()) {
            flowTypeStr = explicitSuffix.toLowerCase();
        } else if (user != null) {
            if (Boolean.TRUE.equals(user.getForceShowAppointment())) {
                flowTypeStr = "appointment";
            } else if (Boolean.TRUE.equals(user.getForceShowBooking())) {
                flowTypeStr = "booking";
            } else if (Boolean.TRUE.equals(user.getForceShowLeads())) {
                flowTypeStr = "lead";
            }
        }

        if ("appointment".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.APPOINTMENT;
        } else if ("booking".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.BOOKING;
        } else if ("lead".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.LEAD_CAPTURE;
        } else if ("support".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.SUPPORT;
        }

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

        // ── Non-SUPPORT flows: load from master JSON + DB overrides ───────────
        FlowConfigDTO masterConfig = loadFlow("master-fields");
        if (masterConfig == null) {
            return Collections.emptyList();
        }

        List<FlowFieldConfig> result = new ArrayList<>();
        Optional<TenantFlowConfig> dbConfigOpt = tenantFlowConfigRepository.findByTenantAndFlowType(user, flowTypeEnum);
        Map<String, FlowFieldConfig> dbFieldMap = new HashMap<>();

        if (dbConfigOpt.isPresent()) {
            try {
                TenantFlowConfigJson configJson = objectMapper.readValue(dbConfigOpt.get().getConfigurationJson(), TenantFlowConfigJson.class);
                if (configJson != null && configJson.getFields() != null) {
                    for (FlowFieldConfig fc : configJson.getFields()) {
                        dbFieldMap.put(fc.getKey(), fc);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse TenantFlowConfig JSON for user: " + user.getId(), e);
            }
        }

        int defaultOrder = 0;
        for (FlowStepDTO step : masterConfig.getSteps()) {
            if (step.getApplicableNiches() != null && !step.getApplicableNiches().isEmpty()) {
                String subCat = user.getBusinessSubType();
                if (subCat == null || !step.getApplicableNiches().contains(subCat)) {
                    continue;
                }
            }

            FlowFieldConfig fc = dbFieldMap.get(step.getDataKey());
            if (fc != null) {
                fc.setFieldType(step.getFieldType());
                fc.setOptions(step.getOptions());
                result.add(fc);
            } else {
                result.add(FlowFieldConfig.builder()
                        .key(step.getDataKey())
                        .enabled(step.isDefaultEnabled())
                        .required(step.isRequired())
                        .order(step.getDisplayOrder() != null ? step.getDisplayOrder() : defaultOrder)
                        .label(step.getQuestion())
                        .fieldType(step.getFieldType())
                        .options(step.getOptions())
                        .build());
            }
            defaultOrder++;
        }

        result.sort(Comparator.comparingInt(FlowFieldConfig::getOrder));
        return result;
    }

    public void saveConfigurableFields(User user, String explicitSuffix, List<FlowFieldConfig> fields) {
        if (fields == null || fields.isEmpty()) {
            log.warn("[FlowConfigService] Attempted to save empty flow fields for user={}, ignoring", user != null ? user.getEmail() : "unknown");
            return;
        }
        String flowTypeStr = "";
        ConversationState.FlowType flowTypeEnum = ConversationState.FlowType.ENQUIRY;

        if (explicitSuffix != null && !explicitSuffix.isBlank()) {
            flowTypeStr = explicitSuffix.toLowerCase();
        } else if (user != null) {
            if (Boolean.TRUE.equals(user.getForceShowAppointment())) {
                flowTypeStr = "appointment";
            } else if (Boolean.TRUE.equals(user.getForceShowBooking())) {
                flowTypeStr = "booking";
            } else if (Boolean.TRUE.equals(user.getForceShowLeads())) {
                flowTypeStr = "lead";
            }
        }

        if ("appointment".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.APPOINTMENT;
        } else if ("booking".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.BOOKING;
        } else if ("lead".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.LEAD_CAPTURE;
        } else if ("support".equals(flowTypeStr)) {
            flowTypeEnum = ConversationState.FlowType.SUPPORT;
        }

        try {
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
            } else {
                dbConfig = TenantFlowConfig.builder()
                        .tenant(user)
                        .flowType(flowTypeEnum)
                        .configurationJson(objectMapper.writeValueAsString(jsonConfig))
                        .templateVersion(1)
                        .build();
            }
            tenantFlowConfigRepository.save(dbConfig);
            log.info("Saved configurable fields for user: {} flowType: {}", user.getEmail(), flowTypeEnum);
        } catch (Exception e) {
            log.error("Error saving configurable fields", e);
            throw new RuntimeException("Failed to save configuration", e);
        }
    }

    public String getFlowGreeting(User user, String explicitSuffix) {
        ConversationState.FlowType flowTypeEnum = resolveFlowTypeEnum(user, explicitSuffix);
        Optional<TenantFlowConfig> dbConfigOpt = tenantFlowConfigRepository.findByTenantAndFlowType(user, flowTypeEnum);
        if (dbConfigOpt.isPresent()) {
            try {
                TenantFlowConfigJson configJson = objectMapper.readValue(dbConfigOpt.get().getConfigurationJson(), TenantFlowConfigJson.class);
                return configJson != null ? configJson.getGreetingMessage() : null;
            } catch (Exception e) {
                log.error("Failed to parse config json", e);
            }
        }
        return null; // or fetch default from master config
    }

    public void saveFlowGreeting(User user, String explicitSuffix, String greetingMessage) {
        ConversationState.FlowType flowTypeEnum = resolveFlowTypeEnum(user, explicitSuffix);
        try {
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
            } else {
                dbConfig = TenantFlowConfig.builder()
                        .tenant(user)
                        .flowType(flowTypeEnum)
                        .templateVersion(1)
                        .build();
            }
            
            jsonConfig.setGreetingMessage(greetingMessage);
            dbConfig.setConfigurationJson(objectMapper.writeValueAsString(jsonConfig));
            tenantFlowConfigRepository.save(dbConfig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save greeting message", e);
        }
    }

    @Transactional
    public void deleteFlowConfig(User user, String explicitSuffix) {
        ConversationState.FlowType flowTypeEnum = resolveFlowTypeEnum(user, explicitSuffix);
        tenantFlowConfigRepository.findByTenantAndFlowType(user, flowTypeEnum)
                .ifPresent(tenantFlowConfigRepository::delete);
    }

    private ConversationState.FlowType resolveFlowTypeEnum(User user, String explicitSuffix) {
        String flowTypeStr = "";
        if (explicitSuffix != null && !explicitSuffix.isBlank()) {
            flowTypeStr = explicitSuffix.toLowerCase();
        } else if (user != null) {
            if (Boolean.TRUE.equals(user.getForceShowAppointment())) flowTypeStr = "appointment";
            else if (Boolean.TRUE.equals(user.getForceShowBooking())) flowTypeStr = "booking";
            else if (Boolean.TRUE.equals(user.getForceShowLeads())) flowTypeStr = "lead";
        }
        if ("appointment".equals(flowTypeStr)) return ConversationState.FlowType.APPOINTMENT;
        if ("booking".equals(flowTypeStr)) return ConversationState.FlowType.BOOKING;
        if ("lead".equals(flowTypeStr)) return ConversationState.FlowType.LEAD_CAPTURE;
        if ("support".equals(flowTypeStr)) return ConversationState.FlowType.SUPPORT;
        return ConversationState.FlowType.ENQUIRY;
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
}

