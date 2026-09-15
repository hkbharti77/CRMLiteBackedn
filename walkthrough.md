# Hybrid Graph RAG — Walkthrough

## Supported RAG upload formats

`POST /api/v1/rag/upload` accepts (case-insensitive):

`.pdf`, `.docx`, `.xlsx`, `.xls`, `.csv`, `.txt`, `.md`, `.markdown`, `.html`, `.htm`, `.json`

Not supported: `.rtf`, images (no OCR), `.exe`, unknown types.

Limits (fail entire file — no silent truncate):

| Property | Default |
|----------|---------|
| `RAG_INGEST_MAX_UPLOAD_BYTES` | 20971520 (also Spring multipart max) |
| `rag.ingestion.excel.max-rows-per-sheet` | 100000 |
| `rag.ingestion.csv.max-rows` | 100000 |
| `rag.ingestion.max-extracted-chars` | 5000000 |

CSV/Excel are converted to semantic lines like `Name: John | Email: ...` before chunking → pgvector (and optional Neo4j).

## VECTOR mode (default, no Neo4j)

1. Leave `RAG_MODE` unset or set `RAG_MODE=VECTOR`.
2. Leave `NEO4J_URI` empty.
3. Start Postgres + Redis + app as usual:

```bash
docker compose up -d db redis
mvn spring-boot:run
```

Existing FAQ + document RAG continues to work. Neo4j beans are not created.

## HYBRID mode (optional Neo4j)

1. Start Neo4j:

```bash
docker compose --profile graph up -d neo4j
```

2. Set environment:

```bash
set RAG_MODE=HYBRID
set NEO4J_URI=bolt://localhost:7687
set NEO4J_USERNAME=neo4j
set NEO4J_PASSWORD=password
```

3. Restart the app. Ingest FAQs/documents as usual — graph MERGE runs best-effort after vector persist.

4. Ask relationship-style questions (compatibility, features, categories). Logs should show `[QueryAnalyzer]`, `[HybridRetrieval]`, `[ContextFusion]`, and `[RAG] Success | Mode: HYBRID`.

## Degrade check

Stop Neo4j while `RAG_MODE=HYBRID`. Vector answers should still return; logs show graph degraded / empty graph hits.

## Useful properties

| Property | Default | Meaning |
|----------|---------|---------|
| `rag.mode` | `VECTOR` | `VECTOR` / `GRAPH` / `HYBRID` |
| `rag.graph.max-depth` | `2` | Multi-hop cap |
| `rag.graph.max-nodes` | `50` | Node visit cap |
| `rag.graph.max-relationships` | `100` | Edge return cap |
| `rag.hybrid.vector-weight` | `0.6` | Fusion weight |
| `rag.hybrid.graph-weight` | `0.4` | Fusion weight |
| `rag.graph.index-version` | `1` | Hybrid cache key namespace |

## Validation checklist

- [x] App compiles with Neo4j optional (`Neo4jEnabledCondition`)
- [x] Unit tests: analyzer, fusion, hybrid degradation, graph ingest safety, RagRetrievalService, PromptBuilder hybrid sections
- [x] `mvn test` / `mvn verify` for Hybrid Graph RAG suites — **34 tests, 0 failures**
- [ ] App starts with `rag.mode=VECTOR` and empty `NEO4J_URI` (manual)
- [ ] Existing FAQ fast-path still bypasses LLM (manual / covered in unit tests)
- [ ] With Neo4j + `HYBRID`, graph evidence appears in prompt sections (manual)
- [ ] Neo4j stop does not crash VECTOR retrieval (covered by HybridRetrievalServiceTest)
- [ ] Tenant A never receives Tenant B graph facts (unit-level tenant id preservation)

## Production readiness (current)

**Score: 8/10** — extension is wired end-to-end with VECTOR default, graceful Neo4j absence, tenant-scoped Cypher, and passing unit suites. Remaining: live Neo4j E2E ingest/retrieve against Docker, and measured HYBRID quality before production enablement.
