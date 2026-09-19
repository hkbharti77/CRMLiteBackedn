package com.chatcrmlite.backend.models.flows.dto;

import com.chatcrmlite.backend.models.flows.FlowFieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowFieldItem {
    private String id;
    private String name;
    private String label;
    private FlowFieldType type;
    private boolean required;
    private String description;
    private List<FlowFieldOption> options;
}
