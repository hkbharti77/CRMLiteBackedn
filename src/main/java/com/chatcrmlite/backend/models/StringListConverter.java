package com.chatcrmlite.backend.models;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (Exception e) {
            log.error("Error serializing List<String> to JSON", e);
            return "[]";
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String trimmed = dbData.trim();
        if (trimmed.equals("[]") || trimmed.equals("\"[]\"") || trimmed.equals("'[]'")) {
            return new ArrayList<>();
        }
        try {
            // Handle cases where string might be double-quoted or quoted JSON string
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 2 && trimmed.contains("[")) {
                trimmed = objectMapper.readValue(trimmed, String.class);
            }
            return objectMapper.readValue(trimmed, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Could not deserialize permissions JSON: '{}'. Defaulting to empty list.", dbData);
            return new ArrayList<>();
        }
    }
}
