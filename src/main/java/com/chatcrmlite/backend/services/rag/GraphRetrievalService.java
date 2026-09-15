package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.config.Neo4jRuntime;
import com.chatcrmlite.backend.dto.rag.GraphEvidence;
import com.chatcrmlite.backend.dto.rag.QueryAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-scoped Neo4j retrieval with controlled multi-hop BFS limits.
 */
@Slf4j
@Service
public class GraphRetrievalService {

    private final Neo4jRuntime neo4jRuntime;

    @Value("${rag.graph.max-depth:2}")
    private int maxDepth;

    @Value("${rag.graph.max-nodes:50}")
    private int maxNodes;

    @Value("${rag.graph.max-relationships:100}")
    private int maxRelationships;

    public GraphRetrievalService(@Autowired(required = false) Neo4jRuntime neo4jRuntime) {
        this.neo4jRuntime = neo4jRuntime;
    }

    public boolean isAvailable() {
        return neo4jRuntime != null && neo4jRuntime.isAvailable();
    }

    private Session openSession() {
        return neo4jRuntime.openSession();
    }

    public List<GraphEvidence> retrieve(QueryAnalysis analysis) {
        if (!isAvailable() || analysis == null || analysis.getTenantId() == null) {
            return List.of();
        }
        if (!analysis.isRequiresGraph()) {
            return List.of();
        }

        long start = System.currentTimeMillis();
        UUID tenantId = analysis.getTenantId();
        String tenantStr = tenantId.toString();
        int depthLimit = analysis.getMaxGraphDepth() > 0 ? analysis.getMaxGraphDepth() : maxDepth;

        try {
            List<String> seedIds = resolveSeedNodes(tenantStr, analysis.getEntities());
            if (seedIds.isEmpty() && analysis.getOriginalQuery() != null) {
                seedIds = resolveSeedNodesByQueryText(tenantStr, analysis.getOriginalQuery());
            }
            if (seedIds.isEmpty() && needsStructuredSeedFallback(analysis)) {
                seedIds = resolveStructuredFallbackSeeds(tenantStr);
            }
            if (seedIds.isEmpty()) {
                log.info("[GraphRetrieval] No seed nodes tenant={} latencyMs={}",
                        tenantId, System.currentTimeMillis() - start);
                return List.of();
            }

            List<GraphEvidence> evidence = traverse(tenantId, tenantStr, seedIds, depthLimit,
                    analysis.getRelationshipHints());
            log.info("[GraphRetrieval] tenant={} seeds={} evidence={} depthLimit={} latencyMs={}",
                    tenantId, seedIds.size(), evidence.size(), depthLimit, System.currentTimeMillis() - start);
            return evidence;
        } catch (Exception e) {
            if (neo4jRuntime != null && neo4jRuntime.looksLikeAuthFailure(e)) {
                neo4jRuntime.disable(e.getMessage());
            } else {
                log.warn("[GraphRetrieval] Failed tenant={} (degrading): {}", tenantId, e.getMessage());
            }
            return List.of();
        }
    }

    /**
     * Aggregate / catalog-column queries often have no named entity seeds.
     * Fall back to tenant Feature/Document nodes so graph path is not a no-op.
     */
    static boolean needsStructuredSeedFallback(QueryAnalysis analysis) {
        if (analysis == null) {
            return false;
        }
        String intent = analysis.getIntent();
        if ("AGGREGATE_LOOKUP".equals(intent) || "STRUCTURED_FACTUAL".equals(intent)) {
            return true;
        }
        List<String> cols = analysis.getMatchedColumns();
        return cols != null && !cols.isEmpty();
    }

    private List<String> resolveStructuredFallbackSeeds(String tenantId) {
        String cypher = """
                MATCH (n)
                WHERE n.tenantId = $tenantId
                  AND (n:Feature OR n:Document OR n:Category OR n:Tag OR n:Chunk)
                  AND n.graphId IS NOT NULL
                RETURN n.graphId AS graphId
                LIMIT 15
                """;
        List<String> ids = new ArrayList<>();
        try (Session session = openSession()) {
            Result result = session.run(cypher, Values.parameters("tenantId", tenantId));
            while (result.hasNext()) {
                String id = result.next().get("graphId").asString(null);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    private List<String> resolveSeedNodes(String tenantId, List<String> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        String cypher = """
                MATCH (n)
                WHERE n.tenantId = $tenantId
                  AND toLower(coalesce(n.name, '')) CONTAINS toLower($name)
                RETURN n.graphId AS graphId
                LIMIT 5
                """;
        try (Session session = openSession()) {
            for (String entity : entities) {
                Result result = session.run(cypher, Values.parameters("tenantId", tenantId, "name", entity));
                while (result.hasNext()) {
                    String id = result.next().get("graphId").asString(null);
                    if (id != null) ids.add(id);
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    private List<String> resolveSeedNodesByQueryText(String tenantId, String query) {
        String cypher = """
                MATCH (n)
                WHERE n.tenantId = $tenantId
                  AND (
                    toLower(coalesce(n.name, '')) CONTAINS toLower($token)
                    OR toLower(coalesce(n.question, '')) CONTAINS toLower($token)
                  )
                RETURN n.graphId AS graphId
                LIMIT 10
                """;
        List<String> ids = new ArrayList<>();
        String[] tokens = query.toLowerCase(Locale.ROOT).split("\\s+");
        try (Session session = openSession()) {
            for (String token : tokens) {
                if (token.length() < 4) continue;
                Result result = session.run(cypher, Values.parameters("tenantId", tenantId, "token", token));
                while (result.hasNext()) {
                    String id = result.next().get("graphId").asString(null);
                    if (id != null) ids.add(id);
                    if (ids.size() >= 10) break;
                }
                if (ids.size() >= 10) break;
            }
        }
        return ids.stream().distinct().toList();
    }

    private List<GraphEvidence> traverse(UUID tenantId, String tenantStr, List<String> seedIds,
                                         int depthLimit, List<String> relationshipHints) {
        List<GraphEvidence> evidence = new ArrayList<>();
        Set<String> visitedNodes = new HashSet<>();
        Set<String> visitedRels = new HashSet<>();
        Queue<Hop> queue = new ArrayDeque<>();

        for (String seed : seedIds) {
            queue.add(new Hop(seed, 0));
            visitedNodes.add(seed);
        }

        String cypher = """
                MATCH (a {graphId: $graphId})-[r]->(b)
                WHERE a.tenantId = $tenantId AND b.tenantId = $tenantId
                  AND r.tenantId = $tenantId
                RETURN a.graphId AS fromId, coalesce(a.name, a.question, a.graphId) AS fromName,
                       type(r) AS relType, r.sourceType AS sourceType, r.sourceId AS sourceId,
                       b.graphId AS toId, coalesce(b.name, b.question, b.graphId) AS toName
                LIMIT $limit
                """;

        try (Session session = openSession()) {
            while (!queue.isEmpty()
                    && visitedNodes.size() < maxNodes
                    && evidence.size() < maxRelationships) {
                Hop hop = queue.poll();
                if (hop.depth >= depthLimit) {
                    continue;
                }

                Result result = session.run(cypher, Values.parameters(
                        "graphId", hop.nodeId,
                        "tenantId", tenantStr,
                        "limit", Math.min(20, maxRelationships - evidence.size())));

                while (result.hasNext()) {
                    Record rec = result.next();
                    String fromId = rec.get("fromId").asString();
                    String toId = rec.get("toId").asString();
                    String relType = rec.get("relType").asString();
                    String relKey = fromId + "|" + relType + "|" + toId;
                    if (visitedRels.contains(relKey)) {
                        continue;
                    }
                    visitedRels.add(relKey);

                    if (relationshipHints != null && !relationshipHints.isEmpty()
                            && hop.depth == 0
                            && !relationshipHints.contains(relType)
                            && evidence.size() > 5) {
                        // Soft filter: still allow early hops of preferred types first
                    }

                    String fromName = asText(rec.get("fromName"));
                    String toName = asText(rec.get("toName"));
                    String sourceType = asText(rec.get("sourceType"));
                    String sourceId = asText(rec.get("sourceId"));

                    evidence.add(GraphEvidence.builder()
                            .tenantId(tenantId)
                            .entity(fromName)
                            .entityId(fromId)
                            .relationship(relType)
                            .target(toName)
                            .targetId(toId)
                            .fact(fromName + " --" + relType + "--> " + toName)
                            .sourceType(sourceType.isBlank() ? "GRAPH" : sourceType)
                            .sourceId(sourceId.isBlank() ? toId : sourceId)
                            .confidence(1.0)
                            .hopDepth(hop.depth + 1)
                            .build());

                    if (!visitedNodes.contains(toId) && visitedNodes.size() < maxNodes) {
                        visitedNodes.add(toId);
                        queue.add(new Hop(toId, hop.depth + 1));
                    }

                    if (evidence.size() >= maxRelationships) {
                        break;
                    }
                }
            }
        }

        log.debug("[GraphRetrieval] traversal nodes={} rels={} maxDepth={}",
                visitedNodes.size(), evidence.size(), depthLimit);
        return evidence;
    }

    private static String asText(org.neo4j.driver.Value value) {
        if (value == null || value.isNull()) return "";
        return value.asString("");
    }

    private record Hop(String nodeId, int depth) {}
}
