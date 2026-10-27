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

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getFieldType() { return fieldType; }
    public void setFieldType(String fieldType) { this.fieldType = fieldType; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }
    public OptionSource getOptionSource() { return optionSource; }
    public void setOptionSource(OptionSource optionSource) { this.optionSource = optionSource; }

    public static FlowFieldConfigBuilder builder() {
        return new FlowFieldConfigBuilder();
    }

    public static class FlowFieldConfigBuilder {
        private String key;
        private boolean enabled;
        private boolean required;
        private int order;
        private String label;
        private String fieldType;
        private List<String> options;
        private OptionSource optionSource = OptionSource.AUTO_DETECT;

        public FlowFieldConfigBuilder key(String key) { this.key = key; return this; }
        public FlowFieldConfigBuilder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public FlowFieldConfigBuilder required(boolean required) { this.required = required; return this; }
        public FlowFieldConfigBuilder order(int order) { this.order = order; return this; }
        public FlowFieldConfigBuilder label(String label) { this.label = label; return this; }
        public FlowFieldConfigBuilder fieldType(String fieldType) { this.fieldType = fieldType; return this; }
        public FlowFieldConfigBuilder options(List<String> options) { this.options = options; return this; }
        public FlowFieldConfigBuilder optionSource(OptionSource optionSource) { this.optionSource = optionSource; return this; }

        public FlowFieldConfig build() {
            return new FlowFieldConfig(key, enabled, required, order, label, fieldType, options, optionSource);
        }
    }
}
