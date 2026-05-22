# AI & RAG Pipeline Audit — CRMLite Backend

## 1. Embedding Pipeline
- **Model**: `AllMiniLmL6V2QuantizedEmbeddingModel` (Local).
- **Pros**: Zero cost per embedding, high privacy (data never leaves the server during embedding).
- **Cons**: 384 dimensions. While good for small datasets, it may lose semantic nuance on very large enterprise knowledge bases compared to OpenAI `text-embedding-3-small`.

## 2. Vector Retrieval & Guardrails
- **Guardrail Strategy**: Sophisticated. Uses `RagGuardrailService` with niche-specific configurations.
- **Deduplication**: Excellent semantic deduplication to prevent redundant LLM calls for the same user intent.
- **Fallbacks**: Uses ` Decision.MENU` as a safe fallback when AI confidence (score) is low.

## 3. Hallucination Prevention
- **Prompt Engineering**: The prompt in `RagRetrievalService` is strict: *"Answer ONLY using the provided context... If the answer is missing... say 'I don't know'"*.
- **Risk**: LLMs can still ignore these instructions if the user is clever with prompt injection.
- **Evidence**: No verification step exists to check if the generated answer actually came from the context (e.g., using a second "fact-check" LLM pass).

## 4. Multi-Tenant AI Isolation
- **Mechanism**: The `tenantId` is passed through the entire retrieval pipeline. Vectors are strictly separated by tenant in the in-memory cache.
- **Context Contamination**: Low risk. A user from Tenant A cannot see chunks from Tenant B because the `LocalVectorStoreService.search` is scoped to `tenantId`.

## 5. Operational Risks
- **Cost Explosion**: Even with Gemini's generous free tier/low cost, a "chatty" user or a bot attack could burn through credits. The 50 hits/minute alert in `RagGuardrailService` is a good first step, but hard quotas per tenant are needed.
- **Latency**: AI responses take 2-5 seconds. This causes a laggy experience on WhatsApp.
- **Chunking Strategy**: Hard-coded truncation at 300 characters in guardrails and 1000 in retrieval. This might cut off important information mid-sentence.
