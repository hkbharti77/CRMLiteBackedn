package com.chatcrmlite.backend.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * FIX #11: Added validation to ensure options consistency with button/list flags
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowStepDTO {

    @NotBlank(message = "Data key cannot be blank")
    private String dataKey;
    
    @NotBlank(message = "Question cannot be blank")
    private String question;

    @Builder.Default
    private boolean usesButtons = false;

    @Builder.Default
    private boolean usesList = false;

    @Builder.Default
    private boolean dynamicSource = false;

    @Builder.Default
    private com.chatcrmlite.backend.dto.flow.FlowFieldConfig.OptionSource optionSource = com.chatcrmlite.backend.dto.flow.FlowFieldConfig.OptionSource.AUTO_DETECT;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Builder.Default
    private List<String> options = new ArrayList<>();

    private String fieldType;

    @Builder.Default
    private boolean required = false;

    @Builder.Default
    private boolean defaultEnabled = true;

    private Integer displayOrder;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    @Builder.Default
    private List<String> applicableNiches = new ArrayList<>();

    /**
     * FIX #11: Validates that options are provided when buttons or lists are enabled
     */
    public void validate() {
        if (!dynamicSource && (usesButtons || usesList) && (options == null || options.isEmpty())) {
            throw new IllegalArgumentException(
                "Options must be provided when usesButtons or usesList is true for step: " + dataKey);
        }
        
        if (!dynamicSource && usesButtons && options != null && options.size() > 3) {
            throw new IllegalArgumentException(
                "Button options limited to 3 items (WhatsApp limit) for step: " + dataKey);
        }
        
        if (!dynamicSource && usesList && options != null && options.size() > 10) {
            throw new IllegalArgumentException(
                "List options limited to 10 items (WhatsApp limit) for step: " + dataKey);
        }
    }

    public String getDataKey() { return dataKey; }
    public void setDataKey(String dataKey) { this.dataKey = dataKey; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public boolean isUsesButtons() { return usesButtons; }
    public void setUsesButtons(boolean usesButtons) { this.usesButtons = usesButtons; }
    public boolean isUsesList() { return usesList; }
    public void setUsesList(boolean usesList) { this.usesList = usesList; }
    public boolean isDynamicSource() { return dynamicSource; }
    public void setDynamicSource(boolean dynamicSource) { this.dynamicSource = dynamicSource; }
    public com.chatcrmlite.backend.dto.flow.FlowFieldConfig.OptionSource getOptionSource() { return optionSource; }
    public void setOptionSource(com.chatcrmlite.backend.dto.flow.FlowFieldConfig.OptionSource optionSource) { this.optionSource = optionSource; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }
    public String getFieldType() { return fieldType; }
    public void setFieldType(String fieldType) { this.fieldType = fieldType; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public boolean isDefaultEnabled() { return defaultEnabled; }
    public void setDefaultEnabled(boolean defaultEnabled) { this.defaultEnabled = defaultEnabled; }
    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    public List<String> getApplicableNiches() { return applicableNiches; }
    public void setApplicableNiches(List<String> applicableNiches) { this.applicableNiches = applicableNiches; }
}
