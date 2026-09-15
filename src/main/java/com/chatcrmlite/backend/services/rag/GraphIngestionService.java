package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.config.Neo4jRuntime;
import com.chatcrmlite.backend.models.BusinessService;
import com.chatcrmlite.backend.models.DocumentChunk;
import com.chatcrmlite.backend.models.FaqItem;
import com.chatcrmlite.backend.services.ai.AiOrchestrator;
import com.chatcrmlite.backend.services.ai.AiRequest;
import com.chatcrmlite.backend.services.ai.AiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Idempotent Neo4j graph ingestion. Never blocks vector RAG on failure.
 * Uses existing AiOrchestrator for optional entity extraction only.
 */
@Slf4j
@Service
public class GraphIngestionService {

    private static final Pattern SAFE_NAME = Pattern.compile("^[\\w\\s\\-./&+]{1,200}$");
    private static final List<String> ALLOWED_REL_TYPES = List.of(
            "HAS_FEATURE", "ABOUT", "IN_CATEGORY", "CONTAINS", "MENTIONS", "RELATED_TO"
    );
    private static final List<String> ALLOWED_ENTITY_TYPES = List.of(
            "BusinessService", "Faq", "Document", "Chunk", "Category", "Feature", "Tag"
    );

    private final Neo4jRuntime neo4jRuntime;
    private final AiOrchestrator aiOrchestrator;
    private final ObjectMapper objectMapper;

    public GraphIngestionService(
            @Autowired(required = false) Neo4jRuntime neo4jRuntime,
            @Autowired(required = false) AiOrchestrator aiOrchestrator,
            ObjectMapper objectMapper) {
        this.neo4jRuntime = neo4jRuntime;
        this.aiOrchestrator = aiOrchestrator;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return neo4jRuntime != null && neo4jRuntime.isAvailable();
    }

    private Session openSession() {
        return neo4jRuntime.openSession();
    }

    private void handleGraphFailure(String action, Exception e) {
        if (neo4jRuntime != null && neo4jRuntime.looksLikeAuthFailure(e)) {
            neo4jRuntime.disable(e.getMessage());
            return;
        }
        log.warn("[GraphIngest] {} failed (vector path unaffected): {}", action, e.getMessage());
    }

    public void ingestFaq(FaqItem faq) {
        if (!isAvailable() || faq == null || faq.getTenantId() == null || faq.getId() == null) {
            return;
        }
        try {
            String tenantId = faq.getTenantId().toString();
            String faqNodeId = nodeId(tenantId, "Faq", faq.getId().toString());
            mergeNode("Faq", faqNodeId, tenantId, faq.getId().toString(), Map.of(
                    "name", safe(faq.getQuestion()),
                    "question", safe(faq.getQuestion()),
                    "answer", truncate(safe(faq.getAnswer()), 2000),
                    "sourceType", "FAQ",
                    "sourceId", faq.getId().toString()
            ));

            if (faq.getCategory() != null && !faq.getCategory().isBlank()) {
                String catName = normalizeName(faq.getCategory());
                String catId = nodeId(tenantId, "Category", catName);
                mergeNode("Category", catId, tenantId, catName, Map.of(
                        "name", catName,
                        "sourceType", "FAQ",
                        "sourceId", faq.getId().toString()
                ));
                mergeRel(faqNodeId, "IN_CATEGORY", catId, tenantId, "FAQ", faq.getId().toString());
            }

            String text = (faq.getQuestion() != null ? faq.getQuestion() : "") + "\n"
                    + (faq.getAnswer() != null ? faq.getAnswer() : "");
            extractAndMerge(tenantId, "FAQ", faq.getId().toString(), text, faqNodeId);
            log.info("[GraphIngest] FAQ ingested tenant={} faqId={}", tenantId, faq.getId());
        } catch (Exception e) {
            handleGraphFailure("FAQ ingest", e);
        }
    }

    public void ingestDocument(UUID tenantId, UUID documentId, String source, String fullText, List<DocumentChunk> chunks) {
        if (!isAvailable() || tenantId == null || documentId == null) {
            return;
        }
        try {
            String tid = tenantId.toString();
            String docNodeId = nodeId(tid, "Document", documentId.toString());
            mergeNode("Document", docNodeId, tid, documentId.toString(), Map.of(
                    "name", safe(source != null ? source : documentId.toString()),
                    "sourceType", "DOCUMENT",
                    "sourceId", documentId.toString()
            ));

            if (chunks != null) {
                for (DocumentChunk chunk : chunks) {
                    if (chunk.getId() == null) continue;
                    String chunkNodeId = nodeId(tid, "Chunk", chunk.getId().toString());
                    mergeNode("Chunk", chunkNodeId, tid, chunk.getId().toString(), Map.of(
                            "name", "chunk-" + chunk.getId(),
                            "documentId", documentId.toString(),
                            "sourceType", "CHUNK",
                            "sourceId", chunk.getId().toString()
                    ));
                    mergeRel(docNodeId, "CONTAINS", chunkNodeId, tid, "DOCUMENT", documentId.toString());
                }
            }

            extractAndMerge(tid, "DOCUMENT", documentId.toString(), truncate(fullText, 4000), docNodeId);
            log.info("[GraphIngest] Document ingested tenant={} documentId={}", tid, documentId);
        } catch (Exception e) {
            handleGraphFailure("Document ingest", e);
        }
    }

    public void ingestBusinessService(BusinessService service, UUID tenantId) {
        if (!isAvailable() || service == null || tenantId == null || service.getId() == null) {
            return;
        }
        try {
            String tid = tenantId.toString();
            String nodeId = nodeId(tid, "BusinessService", service.getId().toString());
            mergeNode("BusinessService", nodeId, tid, service.getId().toString(), Map.of(
                    "name", safe(service.getName()),
                    "description", truncate(safe(service.getDescription()), 2000),
                    "sourceType", "BUSINESS_SERVICE",
                    "sourceId", service.getId().toString()
            ));
            String text = (service.getName() != null ? service.getName() : "") + "\n"
                    + (service.getDescription() != null ? service.getDescription() : "");
            extractAndMerge(tid, "BUSINESS_SERVICE", service.getId().toString(), text, nodeId);
        } catch (Exception e) {
            handleGraphFailure("BusinessService ingest", e);
        }
    }

    private void extractAndMerge(String tenantId, String sourceType, String sourceId, String text, String anchorNodeId) {
        if (aiOrchestrator == null || text == null || text.isBlank()) {
            return;
        }
        try {
            String prompt = """
                    Extract knowledge graph entities and relationships from the text.
                    Return ONLY valid JSON (no markdown) with this schema:
                    {"entities":[{"type":"Feature|BusinessService|Category|Tag","name":"...","confidence":0.0}],"relationships":[{"source":"...","type":"HAS_FEATURE|ABOUT|MENTIONS|RELATED_TO|IN_CATEGORY","target":"...","confidence":0.0}]}
                    Allowed entity types: Feature, BusinessService, Category, Tag.
                    Allowed relationship types: HAS_FEATURE, ABOUT, MENTIONS, RELATED_TO, IN_CATEGORY.
                    Text:
                    """ + truncate(text, 3000);

            AiResponse response = aiOrchestrator.execute(AiRequest.builder()
                    .prompt(prompt)
                    .tenantId(UUID.fromString(tenantId))
                    .complexity(AiRequest.TaskComplexity.LOW)
                    .maxTokens(800)
                    .temperature(0.1)
                    .build());

            if (response == null || response.getContent() == null) {
                return;
            }
            applyExtractionJson(tenantId, sourceType, sourceId, anchorNodeId, response.getContent());
        } catch (Exception e) {
            log.warn("[GraphIngest] AI extraction failed (continuing without graph enrich): {}", e.getMessage());
        }
    }

    void applyExtractionJson(String tenantId, String sourceType, String sourceId, String anchorNodeId, String rawJson) {
        try {
            String json = extractJsonObject(rawJson);
            JsonNode root = objectMapper.readTree(json);
            JsonNode entities = root.path("entities");
            JsonNode relationships = root.path("relationships");

            Map<String, String> nameToNodeId = new java.util.HashMap<>();
            if (entities.isArray()) {
                for (JsonNode ent : entities) {
                    String type = ent.path("type").asText("");
                    String name = normalizeName(ent.path("name").asText(""));
                    double confidence = ent.path("confidence").asDouble(0.5);
                    if (!ALLOWED_ENTITY_TYPES.contains(type) || name.isBlank() || !SAFE_NAME.matcher(name).matches()) {
                        continue;
                    }
                    if (confidence < 0.5) continue;
                    String nid = nodeId(tenantId, type, name.toLowerCase(Locale.ROOT));
                    mergeNode(type, nid, tenantId, name.toLowerCase(Locale.ROOT), Map.of(
                            "name", name,
                            "sourceType", sourceType,
                            "sourceId", sourceId,
                            "confidence", confidence
                    ));
                    nameToNodeId.put(name.toLowerCase(Locale.ROOT), nid);
                    if ("Feature".equals(type) || "BusinessService".equals(type) || "Category".equals(type)) {
                        String rel = "Feature".equals(type) ? "HAS_FEATURE"
                                : "Category".equals(type) ? "IN_CATEGORY" : "MENTIONS";
                        mergeRel(anchorNodeId, rel, nid, tenantId, sourceType, sourceId);
                    }
                }
            }

            if (relationships.isArray()) {
                for (JsonNode rel : relationships) {
                    String type = rel.path("type").asText("");
                    String source = normalizeName(rel.path("source").asText(""));
                    String target = normalizeName(rel.path("target").asText(""));
                    double confidence = rel.path("confidence").asDouble(0.5);
                    if (!ALLOWED_REL_TYPES.contains(type) || source.isBlank() || target.isBlank() || confidence < 0.5) {
                        continue;
                    }
                    String srcId = nameToNodeId.getOrDefault(source.toLowerCase(Locale.ROOT),
                            nodeId(tenantId, "Feature", source.toLowerCase(Locale.ROOT)));
                    String tgtId = nameToNodeId.getOrDefault(target.toLowerCase(Locale.ROOT),
                            nodeId(tenantId, "Feature", target.toLowerCase(Locale.ROOT)));
                    if (!nameToNodeId.containsKey(source.toLowerCase(Locale.ROOT))) {
                        mergeNode("Feature", srcId, tenantId, source.toLowerCase(Locale.ROOT), Map.of(
                                "name", source, "sourceType", sourceType, "sourceId", sourceId));
                    }
                    if (!nameToNodeId.containsKey(target.toLowerCase(Locale.ROOT))) {
                        mergeNode("Feature", tgtId, tenantId, target.toLowerCase(Locale.ROOT), Map.of(
                                "name", target, "sourceType", sourceType, "sourceId", sourceId));
                    }
                    mergeRel(srcId, type, tgtId, tenantId, sourceType, sourceId);
                }
            }
        } catch (Exception e) {
            log.warn("[GraphIngest] Invalid extraction JSON ignored: {}", e.getMessage());
        }
    }

    private void mergeNode(String label, String graphId, String tenantId, String sourceId, Map<String, Object> props) {
        // Label is from allow-list only
        if (!ALLOWED_ENTITY_TYPES.contains(label)) {
            return;
        }
        String cypher = "MERGE (n:" + label + " {graphId: $graphId}) "
                + "SET n.tenantId = $tenantId, n.sourceId = $sourceId, n += $props";
        try (Session session = openSession()) {
            session.executeWrite(tx -> {
                tx.run(cypher, Values.parameters(
                        "graphId", graphId,
                        "tenantId", tenantId,
                        "sourceId", sourceId,
                        "props", props));
                return null;
            });
        }
    }

    private void mergeRel(String fromId, String relType, String toId, String tenantId, String sourceType, String sourceId) {
        if (!ALLOWED_REL_TYPES.contains(relType)) {
            return;
        }
        String cypher = """
                MATCH (a {graphId: $fromId}), (b {graphId: $toId})
                WHERE a.tenantId = $tenantId AND b.tenantId = $tenantId
                MERGE (a)-[r:%s {tenantId: $tenantId, sourceType: $sourceType, sourceId: $sourceId}]->(b)
                """.formatted(relType);
        try (Session session = openSession()) {
            session.executeWrite(tx -> {
                tx.run(cypher, Values.parameters(
                        "fromId", fromId,
                        "toId", toId,
                        "tenantId", tenantId,
                        "sourceType", sourceType,
                        "sourceId", sourceId));
                return null;
            });
        }
    }

    static String nodeId(String tenantId, String label, String sourceId) {
        return tenantId + ":" + label + ":" + sourceId;
    }

    private static String normalizeName(String name) {
        if (name == null) return "";
        return name.trim().replaceAll("\\s+", " ");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String extractJsonObject(String raw) {
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
