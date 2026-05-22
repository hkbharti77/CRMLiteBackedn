package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.FlowConfigDTO;
import com.chatcrmlite.backend.models.User;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
public class FlowConfigService {
    private static final Logger log = LoggerFactory.getLogger(FlowConfigService.class);

    private final ObjectMapper objectMapper;

    @Autowired
    public FlowConfigService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public FlowConfigDTO getFlowConfig(User user) {
        String slug = FlowTriggerEngine.toSlug(user.getBusinessSubType());
        FlowConfigDTO config = loadFlow(slug);
        if (config == null) {
            log.warn("[FlowConfigService] No flow found for slug='{}', falling back to generic", slug);
            config = loadFlow("generic");
        }
        if (config == null) {
            log.error("[FlowConfigService] generic.json missing from classpath — returning empty config");
            return FlowConfigDTO.builder().flowType("ENQUIRY").build();
        }
        return config;
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
