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
    public enum OptionSource {
        STATIC,
        DYNAMIC_SERVICES,
        DYNAMIC_CATEGORIES,
        AUTO_DETECT
    }

    private String key;
    private boolean enabled;
    private boolean required;
    private int order;
    private String label;
    private String fieldType;
    private List<String> options;
    @Builder.Default
    private OptionSource optionSource = OptionSource.AUTO_DETECT;
}
