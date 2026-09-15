package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.config.Neo4jRuntime;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-scoped, upload-driven knowledge catalog.
 * Column names + graph entity labels come from ingested data — not hardcoded niches.
 */
@Slf4j
@Service
public class TenantKnowledgeCatalogService {

    static final int MAX_CATALOG_COLUMNS = 100;

    public record CatalogSnapshot(List<String> columns, List<String> entityNames, boolean hasStructuredData) {
        public static CatalogSnapshot empty() {
            return new CatalogSnapshot(List.of(), List.of(), false);
        }
    }

    /** Split dictionary hits (columns) from searchable entity names. */
    public record CatalogMatches(List<String> columns, List<String> entities) {
        public static CatalogMatches empty() {
            return new CatalogMatches(List.of(), List.of());
        }
    }

    private final Neo4jRuntime neo4jRuntime;

    public TenantKnowledgeCatalogService(@Autowired(required = false) Neo4jRuntime neo4jRuntime) {
        this.neo4jRuntime = neo4jRuntime;
    }

    public boolean isAvailable() {
        return neo4jRuntime != null && neo4jRuntime.isAvailable();
    }

    public void recordStructuredSchema(UUID tenantId, String sourceId, List<String> columns) {
        if (!isAvailable() || tenantId == null || columns == null || columns.isEmpty()) {
            return;
        }
        List<String> incoming = columns.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .toList();
        if (incoming.isEmpty()) {
            return;
        }
        String tid = tenantId.toString();
        try (Session session = neo4jRuntime.openSession()) {
            session.executeWrite(tx -> {
                List<String> existing = new ArrayList<>();
                Result prior = tx.run("""
                        MATCH (k:KnowledgeCatalog {tenantId: $tenantId})
                        RETURN k.columns AS columns
                        LIMIT 1
                        """, Values.parameters("tenantId", tid));
                if (prior.hasNext()) {
                    var cols = prior.next().get("columns");
                    if (!cols.isNull()) {
                        for (var v : cols.values()) {
                            String c = v.asString(null);
                            if (c != null && !c.isBlank()) {
                                existing.add(c);
                            }
                        }
                    }
                }
                List<String> merged = unionColumns(existing, incoming, MAX_CATALOG_COLUMNS);
                tx.run("""
                        MERGE (k:KnowledgeCatalog {tenantId: $tenantId})
                        SET k.columns = $columns,
                            k.hasStructuredData = true,
                            k.updatedAt = datetime(),
                            k.lastSourceId = $sourceId
                        """, Values.parameters(
                        "tenantId", tid,
                        "columns", merged,
                        "sourceId", sourceId != null ? sourceId : ""));
                return null;
            });
        } catch (Exception e) {
            log.warn("[KnowledgeCatalog] Failed to record schema tenant={}: {}", tid, e.getMessage());
        }
    }

    /**
     * Union column dictionaries across uploads (case-insensitive), preserving first-seen casing.
     */
    static List<String> unionColumns(List<String> existing, List<String> incoming, int max) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (List<String> batch : List.of(
                existing != null ? existing : List.<String>of(),
                incoming != null ? incoming : List.<String>of())) {
            for (String c : batch) {
                if (c == null || c.isBlank()) continue;
                String trimmed = c.trim();
                String key = trimmed.toLowerCase(Locale.ROOT);
                if (seen.add(key)) {
                    out.add(trimmed);
                    if (out.size() >= max) {
                        return List.copyOf(out);
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    public CatalogSnapshot load(UUID tenantId) {
        if (!isAvailable() || tenantId == null) {
            return CatalogSnapshot.empty();
        }
        String tid = tenantId.toString();
        List<String> columns = new ArrayList<>();
        Set<String> entities = new LinkedHashSet<>();
        boolean structured = false;
        try (Session session = neo4jRuntime.openSession()) {
            Result catalog = session.run("""
                    MATCH (k:KnowledgeCatalog {tenantId: $tenantId})
                    RETURN k.columns AS columns, coalesce(k.hasStructuredData, false) AS structured
                    LIMIT 1
                    """, Values.parameters("tenantId", tid));
            if (catalog.hasNext()) {
                Record rec = catalog.next();
                structured = rec.get("structured").asBoolean(false);
                var cols = rec.get("columns");
                if (!cols.isNull()) {
                    for (var v : cols.values()) {
                        String c = v.asString(null);
                        if (c != null && !c.isBlank()) {
                            columns.add(c);
                        }
                    }
                }
            }

            Result names = session.run("""
                    MATCH (n)
                    WHERE n.tenantId = $tenantId
                      AND (n:Category OR n:Tag OR n:Feature OR n:BusinessService)
                      AND n.name IS NOT NULL
                    RETURN DISTINCT n.name AS name
                    LIMIT 400
                    """, Values.parameters("tenantId", tid));
            while (names.hasNext()) {
                String name = names.next().get("name").asString(null);
                if (name != null && !name.isBlank()) {
                    entities.add(name.trim());
                }
            }
        } catch (Exception e) {
            log.debug("[KnowledgeCatalog] load failed tenant={}: {}", tid, e.getMessage());
            return CatalogSnapshot.empty();
        }
        if (!columns.isEmpty()) {
            structured = true;
        }
        return new CatalogSnapshot(List.copyOf(columns), List.copyOf(entities), structured);
    }

    /**
     * Match query against catalog: columns (dictionary) and entity names separately.
     */
    public CatalogMatches matchCatalog(String normalizedQuery, CatalogSnapshot catalog) {
        if (normalizedQuery == null || normalizedQuery.isBlank() || catalog == null) {
            return CatalogMatches.empty();
        }
        String q = normalizedQuery.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> columnHits = new LinkedHashSet<>();
        LinkedHashSet<String> entityHits = new LinkedHashSet<>();

        for (String entity : catalog.entityNames()) {
            if (entity == null || entity.length() < 2) continue;
            String e = entity.toLowerCase(Locale.ROOT);
            if (q.contains(e)) {
                entityHits.add(entity);
            }
        }
        for (String col : catalog.columns()) {
            if (col == null || col.length() < 2) continue;
            String c = col.toLowerCase(Locale.ROOT).replace('_', ' ');
            String compact = c.replace(" ", "");
            if (q.contains(c) || q.contains(compact) || tokenOverlap(q, c)) {
                columnHits.add(col);
            }
        }
        return new CatalogMatches(new ArrayList<>(columnHits), new ArrayList<>(entityHits));
    }

    /** @deprecated Prefer {@link #matchCatalog}; kept for callers that want a flat hit list. */
    public List<String> matchQueryTerms(String normalizedQuery, CatalogSnapshot catalog) {
        CatalogMatches m = matchCatalog(normalizedQuery, catalog);
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        hits.addAll(m.entities());
        hits.addAll(m.columns());
        return new ArrayList<>(hits);
    }

    private static boolean tokenOverlap(String query, String columnPhrase) {
        String[] parts = columnPhrase.split("\\s+");
        for (String p : parts) {
            if (p.length() >= 4 && query.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
