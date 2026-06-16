package com.chatcrmlite.backend.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.ArrayList;
import java.util.List;

public class FlowConfigDTO {

    private String flowType;
    private String greetingMessage;

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private List<FlowStepDTO> steps = new ArrayList<>();

    public FlowConfigDTO() {}

    public FlowConfigDTO(String flowType, String greetingMessage, List<FlowStepDTO> steps) {
        this.flowType = flowType;
        this.greetingMessage = greetingMessage;
        this.steps = steps != null ? steps : new ArrayList<>();
    }

    public String getFlowType() { return flowType; }
    public void setFlowType(String flowType) { this.flowType = flowType; }
    public String getGreetingMessage() { return greetingMessage; }
    public void setGreetingMessage(String greetingMessage) { this.greetingMessage = greetingMessage; }
    public List<FlowStepDTO> getSteps() { return steps; }
    public void setSteps(List<FlowStepDTO> steps) { this.steps = steps; }

    public static FlowConfigDTOBuilder builder() {
        return new FlowConfigDTOBuilder();
    }

    public static class FlowConfigDTOBuilder {
        private String flowType;
        private String greetingMessage;
        private List<FlowStepDTO> steps = new ArrayList<>();

        public FlowConfigDTOBuilder flowType(String flowType) { this.flowType = flowType; return this; }
        public FlowConfigDTOBuilder greetingMessage(String greetingMessage) { this.greetingMessage = greetingMessage; return this; }
        public FlowConfigDTOBuilder steps(List<FlowStepDTO> steps) { this.steps = steps; return this; }

        public FlowConfigDTO build() {
            return new FlowConfigDTO(flowType, greetingMessage, steps);
        }
    }
}
