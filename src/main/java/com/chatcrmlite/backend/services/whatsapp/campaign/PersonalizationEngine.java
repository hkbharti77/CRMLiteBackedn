package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalizationEngine {

    private final ObjectMapper objectMapper;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    /**
     * Resolves variables mapping JSON into concrete parameter values for a recipient contact & lead.
     * Example variableMappingJson: {"1": "contact.name", "2": "lead.dealValue"}
     */
    public List<String> renderTemplateParameters(String variableMappingJson, Contact contact, Lead lead, User owner) {
        if (variableMappingJson == null || variableMappingJson.isBlank()) {
            return Collections.emptyList();
        }

        try {
            Map<String, String> mapping = objectMapper.readValue(variableMappingJson, new TypeReference<Map<String, String>>() {});
            List<String> parameters = new ArrayList<>();

            // Sort keys numerically e.g. "1", "2", "3"
            List<String> sortedKeys = new ArrayList<>(mapping.keySet());
            sortedKeys.sort(Comparator.comparingInt(a -> {
                try {
                    return Integer.parseInt(a);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }));

            for (String key : sortedKeys) {
                String token = mapping.get(key);
                String resolvedValue = resolveToken(token, contact, lead, owner);
                parameters.add(resolvedValue);
            }

            return parameters;
        } catch (Exception e) {
            log.error("[PersonalizationEngine] Failed to render parameters for contact={}: {}", contact != null ? contact.getId() : "null", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Resolves a individual token token like "contact.name", "lead.dealValue", "company.name".
     */
    public String resolveToken(String token, Contact contact, Lead lead, User owner) {
        if (token == null || token.isBlank()) {
            return "";
        }

        String cleanedToken = token.trim().toLowerCase();

        switch (cleanedToken) {
            case "contact.name":
                return (contact != null && contact.getName() != null) ? contact.getName() : "Customer";
            case "contact.phone":
            case "contact.waid":
                return (contact != null && contact.getWaId() != null) ? contact.getWaId() : "";
            case "lead.dealvalue":
            case "lead.amount":
                return (lead != null && lead.getDealValue() != null) ? lead.getDealValue().toString() : "0";
            case "lead.status":
                return (lead != null && lead.getStatus() != null) ? lead.getStatus().name() : "";
            case "company.name":
                return (owner != null && owner.getBusinessName() != null) ? owner.getBusinessName() : "CRMLite";
            case "owner.name":
                return (owner != null && owner.getFirstName() != null) ? owner.getFirstName() : "Support Team";
            default:
                // Check custom attributes if any
                if (contact != null && contact.getCustomFields() != null && contact.getCustomFields().containsKey(token)) {
                    Object val = contact.getCustomFields().get(token);
                    return val != null ? val.toString() : "";
                }
                return token; // fallback to raw string if static
        }
    }

    /**
     * Extracts all variable placeholders (e.g. {{1}}, {{2}}) from template body text.
     */
    public List<String> extractPlaceholders(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<String> placeholders = new ArrayList<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            placeholders.add(matcher.group(1).trim());
        }
        return placeholders;
    }

    /**
     * Validates that all required variables in template text are mapped in mapping JSON.
     */
    public boolean validateMapping(String bodyText, String variableMappingJson) {
        List<String> requiredVars = extractPlaceholders(bodyText);
        if (requiredVars.isEmpty()) {
            return true;
        }

        if (variableMappingJson == null || variableMappingJson.isBlank()) {
            return false;
        }

        try {
            Map<String, String> mapping = objectMapper.readValue(variableMappingJson, new TypeReference<Map<String, String>>() {});
            for (String varName : requiredVars) {
                if (!mapping.containsKey(varName) || mapping.get(varName) == null || mapping.get(varName).isBlank()) {
                    log.warn("[PersonalizationEngine] Missing mapping for required variable '{{%s}}'", varName);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
