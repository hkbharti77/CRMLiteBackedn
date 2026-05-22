# AI Cost & Abuse Analysis — CRMLite Backend

## 1. Denial of Wallet (DoW) Attacks
A malicious actor can automate WhatsApp messages to trigger expensive LLM calls.

### [The Vulnerability]
- **Current Guard**: `Bucket4j` limits a single phone number to 10 requests per minute.
- **The Attack**: An attacker uses a virtual SIM farm (100 numbers). 
- **The Impact**: 100 numbers * 10 req/min = **1,000 AI calls per minute**.
- **Cost**: If using Gemini Pro (paid tier), this could cost **$10-$50 per hour** of sustained attack.

## 2. In-Memory Vector Explosion
### [Vulnerability]
There is no quota on the number of documents a tenant can upload. 

- **Scenario**: A malicious tenant (or a compromised account) uploads a 100MB text file.
- **Impact**: 
  1. The `RagIngestionService` creates 100,000 chunks.
  2. The `LocalVectorStoreService` tries to load all 100,000 chunks into the JVM heap.
  3. **Node Crashes with OOM**.
  4. This is a **Denial of Service (DoS)** via resource exhaustion.

## 3. Token Consumption Inefficiency
The `RagRetrievalService` uses a "Stuff" strategy (stuffing context into the prompt).

- Every time a user asks "Price?", the system sends:
  - System Instructions (~200 tokens).
  - Multiple retrieved chunks (~500 - 1000 tokens).
  - User Query (~10 tokens).
- **Average Cost**: ~1200 tokens per message. 
- **Optimization**: The system does NOT use **LLM Caching**. Every request is processed from scratch.

## 4. Abuse Protection Architecture

| Attack Vector | Current Defense | Enterprise Requirement |
| :--- | :--- | :--- |
| **Bot Spam** | Bucket4j (Local) | **Redis-backed Bucket4j** (Global across nodes) |
| **Abuse/Profanity** | `AbuseDetectionService` | Fine; but needs **Tenant-level blocking** of specific waIds. |
| **Quota Exhaustion** | None | **Pre-paid AI Credits** system in the DB. Check credits before calling `ragRetrievalService`. |
| **Embedding Bomb** | None | **Strict Limits**: Max 1,000 chunks or 5MB of text per tenant. |

## 5. Cost Mitigation Strategies
1. **Semantic Caching**: Before calling Gemini, hash the `contextKey` from the Guardrail. If we have a recent answer for the same intent/entities in Redis, return it for $0.
2. **Context Truncation**: Limit context chunks to the top 2-3 instead of an unbounded list.
3. **Model Tiering**: Use **Gemini Flash** (Cheaper) for routine queries and **Gemini Pro** (Expensive) only for complex intent resolution.
