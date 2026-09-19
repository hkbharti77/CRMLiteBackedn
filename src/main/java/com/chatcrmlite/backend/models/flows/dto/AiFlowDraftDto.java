package com.chatcrmlite.backend.models.flows.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiFlowDraftDto {
    private String name;
    private String description;
    private List<FlowFieldItem> fields;
}
