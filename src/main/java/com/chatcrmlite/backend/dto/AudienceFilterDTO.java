package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudienceFilterDTO {
    @Builder.Default
    private int version = 1;
    
    @Builder.Default
    private String logicalOperator = "AND"; // "AND" or "OR"
    
    @Builder.Default
    private List<SegmentRuleDTO> rules = new ArrayList<>();
}
