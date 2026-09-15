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
    private final TenantKnowledgeCatalogService knowledgeCatalogService;

    public GraphIngestionService(
            @Autowired(required = false) Neo4jRuntime neo4jRuntime,
            @Autowired(required = false) AiOrchestrator aiOrchestrator,
            ObjectMapper objectMapper,
            @Autowired(required = false) TenantKnowledgeCatalogService knowledgeCatalogService) {
        this.neo4jRuntime = neo4jRuntime;
        this.aiOrchestrator = aiOrchestrator;
        this.objectMapper = objectMapper;
        this.knowledgeCatalogService = knowledgeCatalogService;
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
        if (text == null || text.isBlank()) {
            return;
        }

        // Deterministic first: Excel/CSV "Header: value | ..." rows work without LLM
        int structured = applyStructuredRowExtraction(tenantId, sourceType, sourceId, anchorNodeId, text);
        if (structured > 0) {
            log.info("[GraphIngest] Structured row enrich applied entities~={} tenant={} sourceId={}",
                    structured, tenantId, sourceId);
        }

        if (aiOrchestrator == null) {
            return;
        }
        try {
            String prompt = """
                    Extract knowledge graph entities and relationships from the text.
                    Reply with ONLY one minified JSON object. No markdown. No comments. No trailing commas.
                    Use double quotes for every key and string value.
                    Cap entities at 25 and relationships at 25.
                    Schema exactly:
                    {"entities":[{"type":"Feature","name":"x","confidence":0.9}],"relationships":[{"source":"x","type":"IN_CATEGORY","target":"y","confidence":0.9}]}
                    Allowed entity types: Feature, BusinessService, Category, Tag.
                    Allowed relationship types: HAS_FEATURE, ABOUT, MENTIONS, RELATED_TO, IN_CATEGORY.
                    Use Feature for primary named items, Category for grouping labels, Tag for attributes/labels.
                    Adapt to whatever domain the text describes — do not invent niche taxonomies.
                    Text:
                    """ + truncate(text, 2500);

            AiResponse response = aiOrchestrator.execute(AiRequest.builder()
                    .prompt(prompt)
                    .tenantId(UUID.fromString(tenantId))
                    .complexity(AiRequest.TaskComplexity.LOW)
                    .maxTokens(600)
                    .temperature(0.0)
                    .build());

            if (response == null || response.getContent() == null || response.getContent().isBlank()) {
                return;
            }
            applyExtractionJson(tenantId, sourceType, sourceId, anchorNodeId, response.getContent());
        } catch (Exception e) {
            log.warn("[GraphIngest] AI extraction failed (continuing without AI enrich): {}", e.getMessage());
        }
    }

    /**
     * Parse Excel/CSV extractor lines like:
     * Columns: Doctor | Specialty | Patients
     * Row 2: Doctor: Ada | Specialty: Cardio | Patients: 40
     * Fully schema-agnostic — no domain header hardcodes.
     */
    int applyStructuredRowExtraction(String tenantId, String sourceType, String sourceId,
                                     String anchorNodeId, String text) {
        if (text == null || text.isBlank() || !isAvailable()) {
            return 0;
        }
        int created = 0;
        String[] lines = text.split("\\R");
        int rowsProcessed = 0;
        java.util.LinkedHashSet<String> discoveredColumns = new java.util.LinkedHashSet<>();
        final int maxTagsPerRow = 8;

        for (String line : lines) {
            if (rowsProcessed >= 200) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.regionMatches(true, 0, "Sheet:", 0, 6)) {
                continue;
            }
            if (trimmed.regionMatches(true, 0, "Columns:", 0, 8)) {
                String cols = trimmed.substring(8).trim();
                for (String c : cols.split("[|,;]")) {
                    String name = c.trim();
                    if (!name.isEmpty()) {
                        discoveredColumns.add(name);
                    }
                }
                continue;
            }
            String payload = trimmed.replaceFirst("(?i)^Row\\s+\\d+:\\s*", "");
            if (!payload.contains(":")) {
                continue;
            }

            Map<String, String> fields = parseNamedCells(payload);
            if (fields.isEmpty()) {
                continue;
            }
            // Preserve original header casing from Columns: when possible; else use keys as-is
            for (String key : fields.keySet()) {
                discoveredColumns.add(key);
            }
            rowsProcessed++;

            String primary = firstNonNumericValue(fields);
            String primaryNode = null;
            if (primary != null) {
                primaryNode = upsertTyped(tenantId, "Feature", primary, sourceType, sourceId, anchorNodeId, "HAS_FEATURE");
                if (primaryNode != null) {
                    created++;
                }
            }

            int tags = 0;
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (tags >= maxTagsPerRow) {
                    break;
                }
                String val = e.getValue();
                if (val == null || val.isBlank() || isMostlyNumeric(val) || val.length() > 80) {
                    continue;
                }
                if (primary != null && val.equalsIgnoreCase(primary)) {
                    continue;
                }
                String tagNode = upsertTyped(tenantId, "Tag", val, sourceType, sourceId, anchorNodeId, "MENTIONS");
                if (tagNode == null) {
                    continue;
                }
                created++;
                tags++;
                if (primaryNode != null) {
                    mergeRel(primaryNode, "RELATED_TO", tagNode, tenantId, sourceType, sourceId);
                }
            }
        }

        if (!discoveredColumns.isEmpty() && knowledgeCatalogService != null) {
            try {
                knowledgeCatalogService.recordStructuredSchema(
                        UUID.fromString(tenantId), sourceId, new java.util.ArrayList<>(discoveredColumns));
            } catch (Exception e) {
                log.debug("[GraphIngest] catalog record skipped: {}", e.getMessage());
            }
        }
        return created;
    }

    private static String firstNonNumericValue(Map<String, String> fields) {
        for (String v : fields.values()) {
            if (v != null && !v.isBlank() && !isMostlyNumeric(v)) {
                return v;
            }
        }
        return fields.isEmpty() ? null : fields.values().iterator().next();
    }

    private static boolean isMostlyNumeric(String v) {
        String t = v.replace(",", "").replace("%", "").trim();
        if (t.isEmpty()) return false;
        int digits = 0;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isDigit(t.charAt(i))) digits++;
        }
        return digits >= Math.ceil(t.length() * 0.6);
    }

    private String upsertTyped(String tenantId, String type, String name, String sourceType, String sourceId,
                               String anchorNodeId, String anchorRel) {
        String normalized = normalizeName(name);
        if (normalized.isBlank() || !SAFE_NAME.matcher(normalized).matches()) {
            return null;
        }
        String nid = nodeId(tenantId, type, normalized.toLowerCase(Locale.ROOT));
        mergeNode(type, nid, tenantId, normalized.toLowerCase(Locale.ROOT), Map.of(
                "name", normalized,
                "sourceType", sourceType,
                "sourceId", sourceId,
                "confidence", 0.95
        ));
        if (anchorNodeId != null && ALLOWED_REL_TYPES.contains(anchorRel)) {
            mergeRel(anchorNodeId, anchorRel, nid, tenantId, sourceType, sourceId);
        }
        return nid;
    }

    static Map<String, String> parseNamedCells(String payload) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        String[] parts = payload.split("\\|");
        for (String part : parts) {
            String p = part.trim();
            int idx = p.indexOf(':');
            if (idx < 0) {
                idx = p.indexOf('=');
            }
            if (idx <= 0) {
                continue;
            }
            String key = p.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = p.substring(idx + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    void applyExtractionJson(String tenantId, String sourceType, String sourceId, String anchorNodeId, String rawJson) {
        try {
            String json = repairExtractionJson(rawJson);
            JsonNode root = objectMapper.readTree(json);
            JsonNode entities = root.path("entities");
            JsonNode relationships = root.path("relationships");

            Map<String, String> nameToNodeId = new java.util.HashMap<>();
            if (entities.isArray()) {
                int n = 0;
                for (JsonNode ent : entities) {
                    if (n++ >= 40) break;
                    String type = normalizeEntityType(ent.path("type").asText(""));
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
                    if ("Feature".equals(type) || "BusinessService".equals(type) || "Category".equals(type) || "Tag".equals(type)) {
                        String rel = "Feature".equals(type) ? "HAS_FEATURE"
                                : "Category".equals(type) ? "IN_CATEGORY" : "MENTIONS";
                        mergeRel(anchorNodeId, rel, nid, tenantId, sourceType, sourceId);
                    }
                }
            }

            if (relationships.isArray()) {
                int n = 0;
                for (JsonNode rel : relationships) {
                    if (n++ >= 40) break;
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
            log.info("[GraphIngest] AI extraction applied entities={} relationships={} sourceId={}",
                    entities.isArray() ? entities.size() : 0,
                    relationships.isArray() ? relationships.size() : 0,
                    sourceId);
        } catch (Exception e) {
            log.warn("[GraphIngest] Invalid extraction JSON ignored after repair: {}", e.getMessage());
        }
    }

    private static String normalizeEntityType(String type) {
        if (type == null) return "";
        String t = type.trim();
        for (String allowed : List.of("Feature", "BusinessService", "Category", "Tag", "Faq", "Document", "Chunk")) {
            if (allowed.equalsIgnoreCase(t)) {
                return allowed;
            }
        }
        // Unknown LLM labels map to Feature — no niche alias list
        return t.isEmpty() ? "" : "Feature";
    }

    /**
     * Best-effort repair for free-model JSON (unquoted keys, single quotes, fences, trailing commas).
     */
    static String repairExtractionJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        String s = raw.trim();
        // Strip markdown fences
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
            s = s.trim();
        }
        s = extractJsonObject(s);

        // Single-quoted strings → double quotes (simple cases)
        s = s.replaceAll("'([^']*)'", "\"$1\"");

        // Unquoted keys: {type: or ,name:
        s = s.replaceAll("([{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)(\\s*:)", "$1\"$2\"$3");

        // Trailing commas before } or ]
        s = s.replaceAll(",\\s*([}\\]])", "$1");

        // If truncated mid-object, close arrays/objects roughly
        if (!isBalancedJson(s)) {
            s = forceCloseJson(s);
        }
        return s;
    }

    private static boolean isBalancedJson(String s) {
        int brace = 0;
        int bracket = 0;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') brace++;
            else if (c == '}') brace--;
            else if (c == '[') bracket++;
            else if (c == ']') bracket--;
        }
        return brace == 0 && bracket == 0;
    }

    private static String forceCloseJson(String s) {
        StringBuilder sb = new StringBuilder(s.trim());
        // Drop trailing incomplete token after last comma/colon
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (Character.isLetterOrDigit(last) || last == '"' || last == ']' || last == '}') {
                break;
            }
            if (last == ',' || last == ':' || Character.isWhitespace(last)) {
                sb.deleteCharAt(sb.length() - 1);
                continue;
            }
            break;
        }
        int brace = 0;
        int bracket = 0;
        boolean inString = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '"' && (i == 0 || sb.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') brace++;
            else if (c == '}') brace--;
            else if (c == '[') bracket++;
            else if (c == ']') bracket--;
        }
        if (inString) {
            sb.append('"');
        }
        while (bracket-- > 0) {
            sb.append(']');
        }
        while (brace-- > 0) {
            sb.append('}');
        }
        return sb.toString();
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
