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
}
