package com.chatcrmlite.backend.services.campaign.segment;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class TagSegmentParser {

    private final ObjectMapper objectMapper;

    public TagSegmentFilter parse(String filterJson) {
        if (filterJson == null || filterJson.trim().isEmpty()) {
            return TagSegmentFilter.builder()
                    .matchMode(TagMatchMode.ANY)
                    .includeTags(new ArrayList<>())
                    .excludeTags(new ArrayList<>())
                    .build();
        }

        filterJson = filterJson.trim();

        if (filterJson.startsWith("{")) {
            // New JSON format
            try {
                TagSegmentFilter filter = objectMapper.readValue(filterJson, TagSegmentFilter.class);
                normalize(filter);
                return filter;
            } catch (Exception e) {
                log.error("Failed to parse advanced tag segment filter: {}", filterJson, e);
                throw new InvalidTagSegmentFilterException("The audience tag segment configuration is invalid.", e);
            }
        } else {
            // Legacy fallback: comma separated
            List<String> tags = new ArrayList<>(Arrays.asList(filterJson.split(",")));
            TagSegmentFilter filter = TagSegmentFilter.builder()
                    .matchMode(TagMatchMode.ANY)
                    .includeTags(tags)
                    .excludeTags(new ArrayList<>())
                    .build();
            normalize(filter);
            return filter;
        }
    }

    private void normalize(TagSegmentFilter filter) {
        if (filter.getMatchMode() == null) {
            filter.setMatchMode(TagMatchMode.ANY);
        }
        if (filter.getIncludeTags() == null) {
            filter.setIncludeTags(new ArrayList<>());
        }
        if (filter.getExcludeTags() == null) {
            filter.setExcludeTags(new ArrayList<>());
        }

        filter.setIncludeTags(normalizeTags(filter.getIncludeTags()));
        filter.setExcludeTags(normalizeTags(filter.getExcludeTags()));
    }

    private List<String> normalizeTags(List<String> rawTags) {
        List<String> normalized = new ArrayList<>();
        for (String tag : rawTags) {
            if (tag != null && !tag.trim().isEmpty()) {
                String clean = tag.trim().toLowerCase(Locale.ROOT);
                if (!normalized.contains(clean)) {
                    normalized.add(clean);
                }
            }
        }
        return normalized;
    }
}
