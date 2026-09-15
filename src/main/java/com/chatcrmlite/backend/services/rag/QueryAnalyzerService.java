package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.dto.rag.QueryAnalysis;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes Hybrid Graph RAG using <b>tenant-uploaded catalog</b> + language patterns.
 * No niche/product hardcoding — new Excel/CSV schemas work without code changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryAnalyzerService {

    /** Language-level patterns only (not business vocabulary). */
    private static final List<String> AGGREGATE_PATTERNS = List.of(
            "how many", "number of", "count of", "total ",
            "more than", "less than", "at least", "at most",
            "highest", "lowest", "most ", "least ",
            "compare", " vs ", "versus", "which has", "which have"
    );

    private static final List<String> RELATION_PATTERNS = List.of(
            "compatible", "compatibility", "related to", "works with",
            "belongs", "relationship", "connected"
    );

    private static final List<String> RELATIONSHIP_HINT_KEYWORDS = List.of(
            "COMPATIBLE_WITH", "HAS_FEATURE", "RELATED_TO", "ABOUT", "IN_CATEGORY", "MENTIONS"
    );

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{2,80})\"|'([^']{2,80})'");

    private final RagRouterService ragRouterService;
    private final TenantKnowledgeCatalogService knowledgeCatalogService;

    @Value("${rag.mode:VECTOR}")
    private String ragModeProperty;

    @Value("${rag.graph.max-depth:2}")
    private int defaultMaxGraphDepth;

    public QueryAnalysis analyze(String query, UUID tenantId) {
        long start = System.currentTimeMillis();
        boolean requiresRag = ragRouterService.requiresRag(query);
        RagMode mode = RagMode.from(ragModeProperty);

        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        TenantKnowledgeCatalogService.CatalogSnapshot catalog = knowledgeCatalogService.load(tenantId);
        TenantKnowledgeCatalogService.CatalogMatches matches =
                knowledgeCatalogService.matchCatalog(normalized, catalog);

        List<String> matchedColumns = dedupePreserveOrder(matches.columns());
        List<String> entities = new ArrayList<>();
        entities.addAll(extractQuotedPhrases(query != null ? query : ""));
        entities.addAll(matches.entities());
        entities = dedupePreserveOrder(entities);
        // Never treat dictionary column labels as Neo4j seed entity names
        entities = excludeColumnLabels(entities, matchedColumns);

        List<String> entityTypes = new ArrayList<>();
        if (!entities.isEmpty()) {
            entityTypes.add("Feature");
            entityTypes.add("Category");
            entityTypes.add("Tag");
        }

        String intent = detectIntent(normalized, entities, matchedColumns, catalog);
        List<String> relationshipHints = detectRelationshipHints(intent);

        boolean catalogHit = !entities.isEmpty() || !matchedColumns.isEmpty()
                || columnMentioned(normalized, catalog);
        boolean aggregate = containsAny(normalized, AGGREGATE_PATTERNS);
        boolean relational = containsAny(normalized, RELATION_PATTERNS);

        boolean graphLikely = catalogHit || aggregate || relational
                || (catalog.hasStructuredData() && requiresRag && mode == RagMode.HYBRID && looksStructuredQuestion(normalized));

        boolean requiresVector;
        boolean requiresGraph;
        switch (mode) {
            case GRAPH -> {
                requiresGraph = requiresRag;
                requiresVector = false;
            }
            case HYBRID -> {
                requiresVector = requiresRag;
                requiresGraph = requiresRag && (graphLikely || catalog.hasStructuredData());
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
                .matchedColumns(matchedColumns)
                .entityTypes(entityTypes)
                .relationshipHints(relationshipHints)
                .requiresGraph(requiresGraph)
                .requiresVector(requiresVector)
                .maxGraphDepth(defaultMaxGraphDepth)
                .requiresRag(requiresRag)
                .build();

        log.info("[QueryAnalyzer] tenant={} mode={} intent={} requiresVector={} requiresGraph={} entities={} matchedCols={} catalogCols={} structured={} latencyMs={}",
                tenantId, mode, intent, requiresVector, requiresGraph, entities.size(), matchedColumns.size(),
                catalog.columns().size(), catalog.hasStructuredData(),
                System.currentTimeMillis() - start);
        return analysis;
    }

    private String detectIntent(String normalized, List<String> entities, List<String> matchedColumns,
                                TenantKnowledgeCatalogService.CatalogSnapshot catalog) {
        boolean columnHit = !matchedColumns.isEmpty() || columnMentioned(normalized, catalog);
        if (containsAny(normalized, AGGREGATE_PATTERNS)
                || (columnHit && containsAny(normalized, List.of("how", "which", "most", "more", "less")))) {
            return "AGGREGATE_LOOKUP";
        }
        if (containsAny(normalized, RELATION_PATTERNS)) {
            return "SERVICE_COMPATIBILITY";
        }
        if (!entities.isEmpty()) {
            return "ENTITY_AWARE";
        }
        if (catalog.hasStructuredData()) {
            return "STRUCTURED_FACTUAL";
        }
        return "GENERAL_FACTUAL";
    }

    private List<String> detectRelationshipHints(String intent) {
        if ("SERVICE_COMPATIBILITY".equals(intent)) {
            return List.of("RELATED_TO", "HAS_FEATURE");
        }
        if ("AGGREGATE_LOOKUP".equals(intent) || "STRUCTURED_FACTUAL".equals(intent) || "ENTITY_AWARE".equals(intent)) {
            return List.of("IN_CATEGORY", "RELATED_TO", "HAS_FEATURE", "MENTIONS");
        }
        return List.copyOf(RELATIONSHIP_HINT_KEYWORDS);
    }

    private static boolean looksStructuredQuestion(String normalized) {
        return normalized.contains("?")
                || containsAny(normalized, List.of("how many", "which", "what", "list", "show"));
    }

    private static boolean columnMentioned(String normalized, TenantKnowledgeCatalogService.CatalogSnapshot catalog) {
        if (catalog == null) return false;
        for (String col : catalog.columns()) {
            if (col == null || col.length() < 2) continue;
            String c = col.toLowerCase(Locale.ROOT).replace('_', ' ');
            if (normalized.contains(c) || normalized.contains(c.replace(" ", ""))) {
                return true;
            }
            for (String part : c.split("\\s+")) {
                if (part.length() >= 4 && normalized.contains(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> excludeColumnLabels(List<String> entities, List<String> columns) {
        if (entities.isEmpty() || columns == null || columns.isEmpty()) {
            return entities;
        }
        Set<String> colKeys = new LinkedHashSet<>();
        for (String c : columns) {
            if (c != null) colKeys.add(c.toLowerCase(Locale.ROOT));
        }
        List<String> out = new ArrayList<>();
        for (String e : entities) {
            if (e != null && !colKeys.contains(e.toLowerCase(Locale.ROOT))) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<String> extractQuotedPhrases(String raw) {
        List<String> out = new ArrayList<>();
        Matcher m = QUOTED.matcher(raw);
        while (m.find()) {
            String q = m.group(1) != null ? m.group(1) : m.group(2);
            if (q != null && !q.isBlank()) {
                out.add(q.trim());
            }
        }
        return out;
    }

    private static List<String> dedupePreserveOrder(List<String> in) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s == null || s.isBlank()) continue;
            String key = s.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static boolean containsAny(String text, List<String> needles) {
        for (String n : needles) {
            if (text.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
