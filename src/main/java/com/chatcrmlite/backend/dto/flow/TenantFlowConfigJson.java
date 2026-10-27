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
public class TenantFlowConfigJson {
    private String greetingMessage;
    private List<FlowFieldConfig> fields;

    public String getGreetingMessage() { return greetingMessage; }
    public void setGreetingMessage(String greetingMessage) { this.greetingMessage = greetingMessage; }
    public List<FlowFieldConfig> getFields() { return fields; }
    public void setFields(List<FlowFieldConfig> fields) { this.fields = fields; }

    public static TenantFlowConfigJsonBuilder builder() {
        return new TenantFlowConfigJsonBuilder();
    }

    public static class TenantFlowConfigJsonBuilder {
        private String greetingMessage;
        private List<FlowFieldConfig> fields;

        public TenantFlowConfigJsonBuilder greetingMessage(String greetingMessage) {
            this.greetingMessage = greetingMessage;
            return this;
        }

        public TenantFlowConfigJsonBuilder fields(List<FlowFieldConfig> fields) {
            this.fields = fields;
            return this;
        }

        public TenantFlowConfigJson build() {
            return new TenantFlowConfigJson(greetingMessage, fields);
        }
    }
}
