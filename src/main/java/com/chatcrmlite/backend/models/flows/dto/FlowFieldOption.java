package com.chatcrmlite.backend.models.flows.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowFieldOption {
    private String label;
    private String value;
}
