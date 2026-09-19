package com.chatcrmlite.backend.services.whatsapp.catalog;

import com.chatcrmlite.backend.dto.ai.catalog.CatalogCandidate;
import com.chatcrmlite.backend.models.CatalogStatus;
import com.chatcrmlite.backend.models.TenantAiCatalog;
import com.chatcrmlite.backend.repositories.TenantAiCatalogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogCandidateService {

    private final TenantAiCatalogRepository catalogRepository;

    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s,;:.!?\"'()\\[\\]{}]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "in", "on", "at", "to", "for", "of", "with", "and", "or", "is", "are", "was",
            "were", "be", "this", "that", "it", "my", "your", "can", "you", "please", "send", "give", "me",
            "show", "want", "need", "share", "i", "we", "hi", "hello", "hey", "tell"
    );

    /**
     * Retrieves top active catalog candidates for the tenant that meet or exceed the relevance threshold.
     */
    public List<CatalogCandidate> findCandidates(UUID tenantId, String userQuery, double minThreshold) {
        if (tenantId == null || userQuery == null || userQuery.isBlank()) {
            return Collections.emptyList();
        }

        List<TenantAiCatalog> activeCatalogs = catalogRepository.findByTenantIdAndStatus(tenantId, CatalogStatus.ACTIVE);
        if (activeCatalogs.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> queryTokens = tokenize(userQuery);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<CatalogCandidate> scoredCandidates = new ArrayList<>();

        for (TenantAiCatalog catalog : activeCatalogs) {
            double score = calculateRelevanceScore(queryTokens, userQuery.toLowerCase(), catalog);
            if (score >= minThreshold) {
                scoredCandidates.add(new CatalogCandidate(
                        catalog.getId(),
                        catalog.getTitle(),
                        catalog.getDescription() != null ? catalog.getDescription() : "",
                        catalog.getAiTriggerInstruction(),
                        Math.round(score * 100.0) / 100.0
                ));
            }
        }

        // Sort descending by score, limit to top 3
        scoredCandidates.sort((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()));
        return scoredCandidates.stream().limit(3).collect(Collectors.toList());
    }

    private double calculateRelevanceScore(Set<String> queryTokens, String rawQueryLower, TenantAiCatalog catalog) {
        String title = catalog.getTitle() != null ? catalog.getTitle().toLowerCase() : "";
        String desc = catalog.getDescription() != null ? catalog.getDescription().toLowerCase() : "";
        String trigger = catalog.getAiTriggerInstruction() != null ? catalog.getAiTriggerInstruction().toLowerCase() : "";

        // Check exact or partial phrase containment
        if (rawQueryLower.contains(title) && !title.isBlank()) {
            return 0.95;
        }

        Set<String> titleTokens = tokenize(title);
        Set<String> triggerTokens = tokenize(trigger);
        Set<String> descTokens = tokenize(desc);

        // Compute token overlaps
        double titleMatchCount = countMatches(queryTokens, titleTokens);
        double triggerMatchCount = countMatches(queryTokens, triggerTokens);
        double descMatchCount = countMatches(queryTokens, descTokens);

        // Weighted score calculation
        double titleWeight = titleTokens.isEmpty() ? 0 : (titleMatchCount / Math.min(queryTokens.size(), titleTokens.size()));
        double triggerWeight = triggerTokens.isEmpty() ? 0 : (triggerMatchCount / Math.min(queryTokens.size(), triggerTokens.size()));
        double descWeight = descTokens.isEmpty() ? 0 : (descMatchCount / Math.max(queryTokens.size(), 1));

        double finalScore = (titleWeight * 0.50) + (triggerWeight * 0.35) + (descWeight * 0.15);

        // Boost if query mentions pricing keywords and document is pricing-related
        boolean queryHasPricing = rawQueryLower.contains("price") || rawQueryLower.contains("pricing") ||
                rawQueryLower.contains("rate") || rawQueryLower.contains("cost") || rawQueryLower.contains("fee") || rawQueryLower.contains("charges");
        boolean catalogHasPricing = title.contains("price") || title.contains("pricing") || trigger.contains("price") || trigger.contains("cost");

        if (queryHasPricing && catalogHasPricing) {
            finalScore = Math.min(1.0, finalScore + 0.30);
        }

        // Boost if query asks for brochure/catalog/doc and catalog title matches
        boolean queryHasDoc = rawQueryLower.contains("brochure") || rawQueryLower.contains("catalog") || rawQueryLower.contains("pdf") || rawQueryLower.contains("plan");
        boolean catalogHasDoc = title.contains("brochure") || title.contains("catalog") || title.contains("pdf") || title.contains("plan");

        if (queryHasDoc && catalogHasDoc) {
            finalScore = Math.min(1.0, finalScore + 0.20);
        }

        return Math.min(1.0, Math.max(0.0, finalScore));
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        return Arrays.stream(WORD_SPLIT.split(text.toLowerCase()))
                .filter(w -> w.length() > 1 && !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }

    private long countMatches(Set<String> queryTokens, Set<String> targetTokens) {
        return queryTokens.stream().filter(targetTokens::contains).count();
    }
}
