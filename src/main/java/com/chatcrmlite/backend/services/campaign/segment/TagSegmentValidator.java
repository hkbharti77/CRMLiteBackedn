package com.chatcrmlite.backend.services.campaign.segment;

import org.springframework.stereotype.Component;

@Component
public class TagSegmentValidator {

    public void validate(TagSegmentFilter filter) {
        if (filter == null) {
            throw new InvalidTagSegmentFilterException("Filter cannot be null");
        }
        if (filter.getMatchMode() == null) {
            throw new InvalidTagSegmentFilterException("MatchMode cannot be null");
        }
        if (filter.getIncludeTags() == null) {
            throw new InvalidTagSegmentFilterException("IncludeTags cannot be null");
        }
        if (filter.getExcludeTags() == null) {
            throw new InvalidTagSegmentFilterException("ExcludeTags cannot be null");
        }
        
        // No explicit validation needed beyond null checks as normalization handles the rest,
        // but this class is explicitly created for future enterprise validation rules.
    }
}
