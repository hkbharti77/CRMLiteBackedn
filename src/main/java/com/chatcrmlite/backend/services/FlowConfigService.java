package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.FlowConfigDTO;
import com.chatcrmlite.backend.dto.FlowStepDTO;
import com.chatcrmlite.backend.dto.flow.FlowFieldConfig;
import com.chatcrmlite.backend.dto.flow.TenantFlowConfigJson;
import com.chatcrmlite.backend.models.ConversationState;
import com.chatcrmlite.backend.models.TenantFlowConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TenantFlowConfigRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FlowConfigService {
    private static final Logger log = LoggerFactory.getLogger(FlowConfigService.class);

    private final ObjectMapper objectMapper;
    private final TenantFlowConfigRepository tenantFlowConfigRepository;

    @Autowired
    public FlowConfigService(ObjectMapper objectMapper, TenantFlowConfigRepository tenantFlowConfigRepository) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.tenantFlowConfigRepository = tenantFlowConfigRepository;
    }

    public FlowConfigDTO getFlowConfig(User user) {
        return getFlowConfig(user, null);
    }

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
        }

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

        // Set the flowType based on the resolved enum
        config.setFlowType(flowTypeEnum.name());

        if (user != null) {
            config = applyTenantConfiguration(user, flowTypeEnum, config);
        }

        return config;
    }

    private FlowConfigDTO applyTenantConfiguration(User tenant, ConversationState.FlowType flowType, FlowConfigDTO masterConfig) {
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
        }

        String masterSlug = "master-fields";
        FlowConfigDTO masterConfig = loadFlow(masterSlug);
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
            // Only include fields that are applicable to this tenant's subcategory
            if (step.getApplicableNiches() != null && !step.getApplicableNiches().isEmpty()) {
                String subCat = user.getBusinessSubType();
                if (subCat == null || !step.getApplicableNiches().contains(subCat)) {
                    continue; // Skip if not applicable
                }
            }

            FlowFieldConfig fc = dbFieldMap.get(step.getDataKey());
            if (fc != null) {
                // If it exists in DB, use its settings but copy non-editable properties from master just in case
                fc.setFieldType(step.getFieldType());
                fc.setOptions(step.getOptions());
                result.add(fc);
            } else {
                // Otherwise create a new config based on master defaults
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

        // Sort by order
        result.sort(Comparator.comparingInt(FlowFieldConfig::getOrder));
        return result;
    }

    public void saveConfigurableFields(User user, String explicitSuffix, List<FlowFieldConfig> fields) {
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
        }

        try {
            TenantFlowConfigJson jsonConfig = new TenantFlowConfigJson(fields);
            String jsonStr = objectMapper.writeValueAsString(jsonConfig);

            Optional<TenantFlowConfig> dbConfigOpt = tenantFlowConfigRepository.findByTenantAndFlowType(user, flowTypeEnum);
            TenantFlowConfig dbConfig;
            if (dbConfigOpt.isPresent()) {
                dbConfig = dbConfigOpt.get();
                dbConfig.setConfigurationJson(jsonStr);
            } else {
                dbConfig = TenantFlowConfig.builder()
                        .tenant(user)
                        .flowType(flowTypeEnum)
                        .configurationJson(jsonStr)
                        .templateVersion(1)
                        .build();
            }
            tenantFlowConfigRepository.save(dbConfig);
            log.info("Saved flow config for user: " + user.getId() + ", flowType: " + flowTypeEnum);
        } catch (Exception e) {
            log.error("Failed to save configurable fields for user: " + user.getId(), e);
            throw new RuntimeException("Failed to save configuration", e);
        }
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

