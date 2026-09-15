# Hybrid Graph RAG Architecture

## 1. Existing RAG architecture

The production path remains:

1. Channel entry (`PublicChatController` / `WhatsAppAiService`)
2. `ConversationMemoryService` + `RagRouterService.requiresRag`
3. `RagRetrievalService.getAiResponse`
4. Embed query (`EmbeddingModel`, 384-d)
5. `FaqMatchingService` exact/vector fast-path
6. `SemanticCacheService`
7. Vector retrieval via `HybridSearchService` (pgvector + pg_trgm RRF)
8. `PromptBuilder` → `AiOrchestrator` → LLM

**Note:** `HybridSearchService` means dense+lexical fusion, not Graph RAG.

## 2. New Hybrid Graph RAG architecture

```
User Query + tenantId (from auth context)
        │
        ▼
QueryAnalyzerService  (wraps RagRouterService)
        │
        ▼
   rag.mode?
   ┌────┼────┐
VECTOR GRAPH HYBRID
   │     │     │
   ▼     ▼     ▼
pgvector Neo4j  both (parallel)
   │     │     │
   └─────┴─────┘
        ▼
ContextFusionService (dedupe, rank, token budget, sources)
        ▼
PromptBuilder (VECTOR_CONTEXT / GRAPH_CONTEXT / SOURCES)
        ▼
AiOrchestrator → LLM → Final Answer
```

Default: `rag.mode=VECTOR` (identical to legacy path when Neo4j is absent).

## 3. pgvector responsibilities

- FAQ embeddings + exact match
- Document chunk embeddings
- Semantic cache
- Dense + trigram hybrid search (`HybridSearchService`)
- Source of truth for document/FAQ text

## 4. Neo4j responsibilities

- Entities and relationships (knowledge layer only)
- Multi-hop traversal with depth/node/relationship caps
- Tenant-scoped graph evidence for relationship questions

PostgreSQL remains source of truth for CRM + vector data. Neo4j is optional.

## 5. Query Analyzer

`QueryAnalyzerService`:

- Calls existing `RagRouterService.requiresRag`
- Detects intent (compatibility, feature, category, FAQ, general)
- Extracts entity hints from tenant `BusinessService` names + keywords
- Sets `requiresGraph` / `requiresVector` from intent + `rag.mode`
- **Never** takes `tenantId` from the LLM

## 6. Graph ingestion

`GraphIngestionService` hooks after:

- `RagIngestionService.ingestText` (documents)
- FAQ create / update / batch in `FaqController`

Flow: persist vector data → optional AI JSON extraction via `AiOrchestrator` → validate → Neo4j `MERGE`.

Failures never block vector indexing.

Stable id: `tenantId:Label:sourceId`

## 7. Graph retrieval

`GraphRetrievalService`:

1. Resolve seed nodes (entity names / query tokens), tenant-filtered
2. BFS with `rag.graph.max-depth`, `max-nodes`, `max-relationships`, cycle set
3. Return `GraphEvidence` (entity, relationship, target, fact, source refs)

## 8. Hybrid retrieval

`HybridRetrievalService` runs vector and/or graph paths per mode; parallel when both needed. No LLM merge.

## 9. Context fusion

`ContextFusionService`:

- Deduplicate by content hash
- Rank with `rag.hybrid.vector-weight` / `graph-weight` when graph evidence exists
- Enforce char budget
- Keep graph facts as structured `A --REL--> B` lines
- Emit `SOURCES` list

## 10. Reasoning flow

`PromptBuilder.buildHybridRagPrompt` instructs the model to:

- Use vector vs graph sections correctly
- Not invent entities/relationships
- Prefer source-backed facts
- Admit insufficient evidence

Tenant isolation is enforced before prompt construction.

## 11. Tenant isolation

- SQL: existing `tenant_id = ?`
- Neo4j: every match/traverse filters `n.tenantId = $tenantId` (and relationship `tenantId`)
- Cache keys include tenant id; hybrid keys also include mode + `rag.graph.index-version`
- Cross-tenant leakage must be impossible at retrieval

## 12. Failure handling

| Failure | Behavior |
|---------|----------|
| Neo4j down | VECTOR continues |
| Vector empty/fail | GRAPH continues if evidence |
| Both empty | Persona / insufficient-context |
| LLM down | Existing AiOrchestrator + circuit breaker |
| AI extraction fail | Log; vector ingest continues |

## 13. Configuration

```properties
rag.mode=${RAG_MODE:VECTOR}
neo4j.uri=${NEO4J_URI:}
neo4j.username=${NEO4J_USERNAME:}
neo4j.password=${NEO4J_PASSWORD:}
rag.graph.max-depth=2
rag.graph.max-nodes=50
rag.graph.max-relationships=100
rag.hybrid.vector-weight=0.6
rag.hybrid.graph-weight=0.4
rag.graph.index-version=1
```

Neo4j beans load only when `neo4j.uri` is non-blank (`Neo4jEnabledCondition`). Spring Neo4j auto-config is excluded from the main application.

## 14. Feature flag

- `VECTOR` — existing path (default)
- `GRAPH` — Neo4j only
- `HYBRID` — both

## 15. Deployment

- Docker Compose Neo4j service under profile `graph`: `docker compose --profile graph up`
- App does not `depends_on` Neo4j
- Env vars for URI/username/password; never hardcode secrets

## 16. Testing

Unit tests cover analyzer, fusion, hybrid degradation, graph ingest safety, and extended `RagRetrievalServiceTest`.

Neo4j Testcontainers E2E is optional; when Neo4j is unavailable, graph services return empty and VECTOR mode still passes.

## 17. Limitations

- v1 graph excludes Contact/Lead PII nodes
- No invented Product label — uses `BusinessService`
- Source attribution in prompts is new; answer-level citation UI is not required
- Graph quality depends on ingestion + optional AI extraction
- Hybrid quality should be measured (relationship / multi-hop questions) before enabling `HYBRID` in production

## Modules reused (not duplicated)

`EmbeddingModel`, `FaqMatchingService`, `HybridSearchService`, `AiOrchestrator`, `SemanticCacheService`, `PromptBuilder` (extended), `RagIngestionService` chunk pipeline, tenant SQL filters.

## New modules

`GraphConfig`, `Neo4jEnabledCondition`, `QueryAnalyzerService`, `GraphIngestionService`, `GraphRetrievalService`, `HybridRetrievalService`, `ContextFusionService`, DTOs under `dto.rag`, `RagMode`.
