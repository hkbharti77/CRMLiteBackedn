# Hybrid Graph RAG — Phase 0 Audit

**Date:** 2026-09-15  
**Scope:** Extend existing pgvector FAQ/document RAG with optional Neo4j graph layer.  
**Spring Boot:** 3.4.3 | **LangChain4j:** 0.35.0 | **Neo4j today:** none

---

## Existing RAG flow

```
Channel (PublicChat / WhatsAppAiService)
  → ConversationMemoryService + RagRouterService.requiresRag
  → RagRetrievalService.getAiResponse / getVoiceAiResponse
       1. AIQuotaService.checkAndEnforceQuota
       2. EmbeddingModel.embed(query)          [AllMiniLmL6V2Quantized, 384-d]
       3. FaqMatchingService.findBestMatch     [exact SQL → pgvector FAQ]
          └─ high confidence → return FAQ answer (no LLM)
       4. SemanticCacheService.getCachedResponse
          └─ hit → return cached (no LLM)
       5. if context.requiresRag:
            HybridSearchService.hybridSearch   [pgvector + pg_trgm RRF]
       6. PromptBuilder.buildRagPrompt / buildVoiceRagPrompt
       7. AiOrchestrator.execute(AiRequest)
       8. HallucinationDetector.check          [chat path]
       9. SemanticCacheService.putCachedResponse
  → response string
```

**Naming collision:** `HybridSearchService` = vector + trigram RRF (NOT Graph RAG).  
New orchestration will be `HybridRetrievalService`.

---

## Ownership map (reuse rules)

| # | Concern | Class / method | Rule |
|---|---------|----------------|------|
| 1 | RAG entry | `RagRetrievalService.getAiResponse` / `getVoiceAiResponse` | **Extend** |
| 2 | Query/intent | `RagRouterService.requiresRag` + `ConversationContext` | **Wrap** via `QueryAnalyzerService` |
| 3 | Embeddings | `RagConfig.embeddingModel` | **Do not duplicate** |
| 4 | FAQ exact + vector | `FaqMatchingService.findBestMatch` | **Do not duplicate** |
| 5 | Document retrieval | `HybridSearchService.hybridSearch` | **Do not duplicate** |
| 6 | Ingestion | `RagIngestionService` + FAQ writes in `FaqController` | **Hook** graph ingest after persist |
| 7 | Prompt | `PromptBuilder` | **Extend** |
| 8 | LLM | `AiOrchestrator` | **Do not duplicate** |
| 9 | Tenant filter | SQL `tenant_id = ?` in FAQ/hybrid/cache | **Reuse**; Neo4j Cypher must mirror |
| 10 | Cache | `SemanticCacheService` | **Reuse**; include graph version in hybrid keys |
| 11 | Fallback | Circuit breaker, orchestrator failover, `AiEnabledCondition` | **Preserve** |
| 12 | Source attribution | Chunk `metadata.source` at ingest; not in answers today | **Add** in fusion/prompt |

---

## Insertion point for Hybrid Graph RAG

Inside `RagRetrievalService.getAiResponse` / `getVoiceAiResponse`, **after** FAQ fast-path + semantic cache miss:

```
QueryAnalyzerService.analyze(...)
  → HybridRetrievalService.retrieve(...)   [mode: VECTOR | GRAPH | HYBRID]
  → ContextFusionService.fuse(...)
  → PromptBuilder (VECTOR_CONTEXT / GRAPH_CONTEXT / SOURCES)
  → AiOrchestrator.execute(...)
```

Default: `rag.mode=VECTOR` → behavior equivalent to today when Neo4j absent.

---

## New components required

| Component | Why missing |
|-----------|-------------|
| `GraphConfig` | No Neo4j |
| `GraphIngestionService` | No graph write path |
| `GraphRetrievalService` | No multi-hop graph retrieval |
| `QueryAnalyzerService` | Only boolean `requiresRag` today |
| `HybridRetrievalService` | No vector+graph orchestration |
| `ContextFusionService` | PromptBuilder only joins string chunks |
| DTOs (`QueryAnalysis`, `RetrievalResult`, …) | No hybrid result model |
| `rag.mode` + graph/hybrid properties | No feature flag |

---

## Files that must NOT be duplicated

- Embedding model bean / embedding generation path
- `FaqMatchingService`
- `HybridSearchService` (vector+trigram)
- `AiOrchestrator` / AI providers
- Tenant SQL filtering
- `PromptBuilder` (extend only)
- `SemanticCacheService` core
- `RagIngestionService` chunk pipeline

---

## Domain graph nodes (v1)

From real CRM entities only (no invented Product):

- `BusinessService`, `Faq`, `Document`, `Chunk`, `Category`, `Feature`, `Tag`
- Out of v1: Contact/Lead PII

Stable id: `tenantId:label:sourceId` — always `MERGE`.

---

## Failure degradation (required)

| Case | Behavior |
|------|----------|
| Neo4j down | VECTOR continues |
| pgvector empty/fail | GRAPH continues if evidence |
| Both empty | Existing persona / insufficient-context |
| LLM down | Existing AiOrchestrator / CB fallbacks |
