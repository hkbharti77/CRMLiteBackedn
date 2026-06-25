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
}
