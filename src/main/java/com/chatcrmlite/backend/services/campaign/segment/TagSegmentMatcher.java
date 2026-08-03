package com.chatcrmlite.backend.services.campaign.segment;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tag;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class TagSegmentMatcher {

    public boolean matches(Contact contact, TagSegmentFilter filter) {
        if (contact == null) return false;

        List<String> contactTags = contact.getTags() == null ? List.of() : contact.getTags().stream()
                .filter(t -> t.getName() != null)
                .map(t -> t.getName().trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());

        // Rule 3: Exclude always wins
        for (String excludeTag : filter.getExcludeTags()) {
            if (contactTags.contains(excludeTag)) {
                return false;
            }
        }

        // Rule 4: Empty Include + Exclude (if includes are empty, and we haven't been excluded, then it matches)
        if (filter.getIncludeTags().isEmpty()) {
            return true;
        }

        // Rule 1: Match ANY
        if (filter.getMatchMode() == TagMatchMode.ANY) {
            for (String includeTag : filter.getIncludeTags()) {
                if (contactTags.contains(includeTag)) {
                    return true;
                }
            }
            return false;
        }

        // Rule 2: Match ALL
        if (filter.getMatchMode() == TagMatchMode.ALL) {
            for (String includeTag : filter.getIncludeTags()) {
                if (!contactTags.contains(includeTag)) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }
}
