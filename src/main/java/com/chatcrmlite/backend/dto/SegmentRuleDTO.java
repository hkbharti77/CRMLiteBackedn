package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentRuleDTO {
    private String field;
    private String operator;
    private Object value;
    
    // For operators like BETWEEN or IN, value might be a list.
    public List<String> getValueAsList() {
        if (value instanceof List) {
            return (List<String>) value;
        }
        return List.of();
    }
    
    public String getValueAsString() {
        if (value != null && !(value instanceof List)) {
            return value.toString();
        }
        return "";
    }
}
