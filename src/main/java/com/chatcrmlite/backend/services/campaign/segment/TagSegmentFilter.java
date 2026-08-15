package com.chatcrmlite.backend.services.campaign.segment;

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
public class TagSegmentFilter {
    private Integer version;
    private TagMatchMode matchMode;
    @Builder.Default
    private List<String> includeTags = new ArrayList<>();
    @Builder.Default
    private List<String> excludeTags = new ArrayList<>();
}
