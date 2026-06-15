package com.chatcrmlite.backend.dto.flow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowFieldConfig {
    private String key;
    private boolean enabled;
    private boolean required;
    private int order;
    private String label;
    private String fieldType;
    private List<String> options;
}
