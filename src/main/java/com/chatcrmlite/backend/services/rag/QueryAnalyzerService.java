package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.dto.rag.QueryAnalysis;
import com.chatcrmlite.backend.models.BusinessService;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.memory.RagRouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Extends existing RagRouterService with entity/intent hints for Hybrid Graph RAG.
 * tenantId always comes from the caller — never from the LLM.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryAnalyzerService {

    private static final List<String> GRAPH_KEYWORDS = List.of(
            "compatible", "compatibility", "related", "feature", "features", "supports",
            "category", "categories", "relationship", "belongs", "mentions", "about",
            "which products", "which services", "compare", "vs", "versus"
    );

    private static final List<String> RELATIONSHIP_HINT_KEYWORDS = List.of(
            "COMPATIBLE_WITH", "HAS_FEATURE", "RELATED_TO", "ABOUT", "IN_CATEGORY", "MENTIONS"
    );

    private static final Pattern WORD = Pattern.compile("[a-z0-9]{3,}");

    private final RagRouterService ragRouterService;
    private final BusinessServiceRepository businessServiceRepository;
    private final UserRepository userRepository;

    @Value("${rag.mode:VECTOR}")
    private String ragModeProperty;

    @Value("${rag.graph.max-depth:2}")
    private int defaultMaxGraphDepth;

    public QueryAnalysis analyze(String query, UUID tenantId) {
        long start = System.currentTimeMillis();
        boolean requiresRag = ragRouterService.requiresRag(query);
        RagMode mode = RagMode.from(ragModeProperty);

        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<String> entities = extractEntities(normalized, tenantId);
        List<String> entityTypes = new ArrayList<>();
        if (!entities.isEmpty()) {
            entityTypes.add("BusinessService");
        }
        if (containsAny(normalized, List.of("feature", "features", "supports", "bluetooth", "wifi"))) {
            entityTypes.add("Feature");
        }
        if (containsAny(normalized, List.of("category", "categories"))) {
            entityTypes.add("Category");
        }

        String intent = detectIntent(normalized, entities);
        List<String> relationshipHints = detectRelationshipHints(normalized, intent);

        boolean graphLikely = !entities.isEmpty()
                || containsAny(normalized, GRAPH_KEYWORDS)
                || intent.contains("COMPATIBILITY")
                || intent.contains("FEATURE")
                || intent.contains("CATEGORY");

        boolean requiresVector;
        boolean requiresGraph;
        switch (mode) {
            case GRAPH -> {
                requiresGraph = requiresRag;
                requiresVector = false;
            }
            case HYBRID -> {
                requiresVector = requiresRag;
                requiresGraph = requiresRag && graphLikely;
            }
            default -> {
                requiresVector = requiresRag;
                requiresGraph = false;
            }
        }

        QueryAnalysis analysis = QueryAnalysis.builder()
                .originalQuery(query)
                .tenantId(tenantId)
                .intent(intent)
                .entities(entities)
                .entityTypes(entityTypes)
                .relationshipHints(relationshipHints)
                .requiresGraph(requiresGraph)
                .requiresVector(requiresVector)
                .maxGraphDepth(defaultMaxGraphDepth)
                .requiresRag(requiresRag)
                .build();

        log.info("[QueryAnalyzer] tenant={} mode={} intent={} requiresVector={} requiresGraph={} entities={} latencyMs={}",
                tenantId, mode, intent, requiresVector, requiresGraph, entities.size(),
                System.currentTimeMillis() - start);
        return analysis;
    }

    private List<String> extractEntities(String normalizedQuery, UUID tenantId) {
        Set<String> found = new LinkedHashSet<>();
        try {
            User owner = userRepository.findById(tenantId).orElse(null);
            if (owner != null) {
                List<BusinessService> services = businessServiceRepository.findByOwner(owner);
                for (BusinessService service : services) {
                    if (service.getName() == null || service.getName().isBlank()) continue;
                    String name = service.getName().toLowerCase(Locale.ROOT).trim();
                    if (name.length() >= 3 && normalizedQuery.contains(name)) {
                        found.add(service.getName().trim());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[QueryAnalyzer] BusinessService lookup skipped: {}", e.getMessage());
        }

        // Capitalized multi-word phrases as soft entity hints (no LLM)
        String[] tokens = normalizedQuery.split("\\s+");
        for (String token : tokens) {
            if (WORD.matcher(token).matches() && token.length() >= 4
                    && List.of("bluetooth", "wifi", "pricing", "refund", "warranty").contains(token)) {
                found.add(Character.toUpperCase(token.charAt(0)) + token.substring(1));
            }
        }
        return new ArrayList<>(found);
    }

    private String detectIntent(String normalized, List<String> entities) {
        if (containsAny(normalized, List.of("compatible", "compatibility", "works with", "related to"))) {
            return "SERVICE_COMPATIBILITY";
        }
        if (containsAny(normalized, List.of("feature", "features", "supports", "support"))) {
            return "FEATURE_LOOKUP";
        }
        if (containsAny(normalized, List.of("category", "categories"))) {
            return "CATEGORY_LOOKUP";
        }
        if (containsAny(normalized, List.of("faq", "question", "how do", "how to", "what is", "what are"))) {
            return "FAQ_FACTUAL";
        }
        if (!entities.isEmpty()) {
            return "ENTITY_AWARE";
        }
        return "GENERAL_FACTUAL";
    }

    private List<String> detectRelationshipHints(String normalized, String intent) {
        List<String> hints = new ArrayList<>();
        if (intent.equals("SERVICE_COMPATIBILITY") || containsAny(normalized, List.of("compatible", "related"))) {
            hints.add("RELATED_TO");
        }
        if (intent.equals("FEATURE_LOOKUP") || containsAny(normalized, List.of("feature", "supports"))) {
            hints.add("HAS_FEATURE");
        }
        if (intent.equals("CATEGORY_LOOKUP") || containsAny(normalized, List.of("category"))) {
            hints.add("IN_CATEGORY");
        }
        if (containsAny(normalized, List.of("about", "mentions"))) {
            hints.add("ABOUT");
            hints.add("MENTIONS");
        }
        if (hints.isEmpty()) {
            hints.addAll(List.of("HAS_FEATURE", "ABOUT", "MENTIONS", "RELATED_TO"));
        }
        return hints.stream().filter(RELATIONSHIP_HINT_KEYWORDS::contains).distinct().toList();
    }

    private boolean containsAny(String text, List<String> needles) {
        for (String n : needles) {
            if (text.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
