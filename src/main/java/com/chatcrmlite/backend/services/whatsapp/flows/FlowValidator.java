package com.chatcrmlite.backend.services.whatsapp.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
public class FlowValidator {

    private final ObjectMapper objectMapper;

    public FlowValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validates internal CRM fields configuration.
     */
    public void validateFieldsConfig(String name, String fieldsConfigJson) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Flow name cannot be empty");
        }
        if (name.length() > 60) {
            throw new IllegalArgumentException("Flow name cannot exceed 60 characters");
        }

        if (fieldsConfigJson == null || fieldsConfigJson.isBlank()) {
            throw new IllegalArgumentException("Fields configuration cannot be empty");
        }

        try {
            JsonNode array = objectMapper.readTree(fieldsConfigJson);
            if (!array.isArray()) {
                throw new IllegalArgumentException("Fields configuration must be a JSON array");
            }
            if (array.size() == 0) {
                throw new IllegalArgumentException("Flow must contain at least one field");
            }
            if (array.size() > 20) {
                throw new IllegalArgumentException("Flow cannot contain more than 20 fields per screen");
            }

            Set<String> fieldNames = new HashSet<>();
            for (JsonNode field : array) {
                String fieldName = field.path("name").asText("").trim().toLowerCase();
                if (fieldName.isBlank()) {
                    throw new IllegalArgumentException("Field name cannot be empty");
                }
                if (fieldNames.contains(fieldName)) {
                    throw new IllegalArgumentException("Duplicate field name found: '" + fieldName + "'");
                }
                fieldNames.add(fieldName);

                String type = field.path("type").asText("TEXT").toUpperCase();
                if ("SELECT".equals(type) || "DROPDOWN".equals(type) || "RADIO".equals(type)) {
                    JsonNode options = field.path("options");
                    if (!options.isArray() || options.size() == 0) {
                        throw new IllegalArgumentException("Dropdown/Radio field '" + fieldName + "' must have at least one option");
                    }
                }
            }
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid fields JSON structure: " + e.getMessage());
        }
    }

    /**
     * Validates compiled Meta Flow JSON schema.
     */
    public void validateCompiledMetaJson(String flowJson) {
        if (flowJson == null || flowJson.isBlank()) {
            throw new IllegalArgumentException("Compiled Flow JSON is empty");
        }
        try {
            JsonNode root = objectMapper.readTree(flowJson);
            String ver = root.path("version").asText("");
            if (ver.isBlank() || (!ver.startsWith("6.") && !ver.startsWith("7."))) {
                throw new IllegalArgumentException("Meta Flow JSON must specify a valid version (e.g. 7.0, 6.3)");
            }
            if (!root.has("screens") || !root.path("screens").isArray() || root.path("screens").size() == 0) {
                throw new IllegalArgumentException("Meta Flow JSON must specify at least one screen");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Compiled Meta Flow JSON is invalid: " + e.getMessage());
        }
    }
}
