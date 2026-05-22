package com.chatcrmlite.backend.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.ArrayList;
import java.util.List;

public class FlowConfigDTO {

    private String flowType;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private List<FlowStepDTO> steps = new ArrayList<>();

    public FlowConfigDTO() {}

    public FlowConfigDTO(String flowType, List<FlowStepDTO> steps) {
        this.flowType = flowType;
        this.steps = steps != null ? steps : new ArrayList<>();
    }

    public String getFlowType() { return flowType; }
    public void setFlowType(String flowType) { this.flowType = flowType; }
    public List<FlowStepDTO> getSteps() { return steps; }
    public void setSteps(List<FlowStepDTO> steps) { this.steps = steps; }

    public static FlowConfigDTOBuilder builder() {
        return new FlowConfigDTOBuilder();
    }

    public static class FlowConfigDTOBuilder {
        private String flowType;
        private List<FlowStepDTO> steps = new ArrayList<>();

        public FlowConfigDTOBuilder flowType(String flowType) { this.flowType = flowType; return this; }
        public FlowConfigDTOBuilder steps(List<FlowStepDTO> steps) { this.steps = steps; return this; }

        public FlowConfigDTO build() {
            return new FlowConfigDTO(flowType, steps);
        }
    }
}
